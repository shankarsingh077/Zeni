"""
Zeni Session Manager
Manages individual client sessions with conversation state and streaming coordination.
"""

import asyncio
import time
import uuid
from datetime import datetime
from typing import Optional, Dict, Any, AsyncGenerator
from dataclasses import dataclass, field

from fastapi import WebSocket

from .protocol import (
    SessionState, Language, ConversationTurn, ConversationHistory,
    SessionConfig, SessionAckMessage, StateChangeMessage, ErrorMessage,
    PlaybackStopMessage, TranscriptPartialMessage, TranscriptFinalMessage,
    LLMTokenMessage, LLMCompleteMessage, AudioResponseMessage
)
from .config import config
from .logging import get_logger

logger = get_logger("session")


@dataclass
class Session:
    """
    Represents a single client session with all its state.
    """
    session_id: str
    websocket: WebSocket
    config: SessionConfig
    
    # State management
    state: SessionState = SessionState.IDLE
    previous_state: Optional[SessionState] = None
    
    # Conversation memory
    conversation_history: ConversationHistory = field(default_factory=ConversationHistory)
    
    # Stream handles (set during processing)
    asr_task: Optional[asyncio.Task] = None
    llm_task: Optional[asyncio.Task] = None
    tts_task: Optional[asyncio.Task] = None
    
    # Interruption control
    interrupt_event: asyncio.Event = field(default_factory=asyncio.Event)
    final_transcript_event: asyncio.Event = field(default_factory=asyncio.Event)
    
    # Language state
    detected_language: Language = Language.ENGLISH
    
    # Timing
    created_at: datetime = field(default_factory=datetime.now)
    last_activity: datetime = field(default_factory=datetime.now)
    
    # Audio buffer
    audio_buffer: bytearray = field(default_factory=bytearray)
    
    # Sequence tracking
    audio_sequence: int = 0
    llm_token_sequence: int = 0
    
    # Robot connection state
    robot_connected: bool = False
    
    def __post_init__(self):
        """Initialize session."""
        self.interrupt_event = asyncio.Event()
        self.final_transcript_event = asyncio.Event()
    
    async def send_message(self, message: Any) -> None:
        """Send a message to the client."""
        try:
            if hasattr(message, 'model_dump'):
                await self.websocket.send_json(message.model_dump())
            else:
                await self.websocket.send_json(message)
            self.last_activity = datetime.now()
        except Exception as e:
            logger.error("send_message_failed", session_id=self.session_id, error=str(e))
    
    async def transition_state(self, new_state: SessionState) -> None:
        """Transition to a new state and notify client."""
        if new_state == self.state:
            return
        
        self.previous_state = self.state
        self.state = new_state
        
        logger.info(
            "state_transition",
            session_id=self.session_id,
            from_state=self.previous_state.value if self.previous_state else None,
            to_state=new_state.value
        )
        
        await self.send_message(StateChangeMessage(
            state=new_state,
            previous_state=self.previous_state
        ))
    
    async def handle_interrupt(self) -> None:
        """Handle an interrupt signal - cancel all active streams."""
        logger.info("handling_interrupt", session_id=self.session_id, current_state=self.state.value)
        
        # Set interrupt flag
        self.interrupt_event.set()
        
        # Cancel all active tasks
        tasks_to_cancel = [self.asr_task, self.llm_task, self.tts_task]
        for task in tasks_to_cancel:
            if task and not task.done():
                task.cancel()
                try:
                    await asyncio.wait_for(asyncio.shield(task), timeout=0.1)
                except (asyncio.CancelledError, asyncio.TimeoutError):
                    pass
        
        # Clear tasks
        self.asr_task = None
        self.llm_task = None
        self.tts_task = None
        
        # Clear buffers
        self.audio_buffer.clear()
        
        # Send stop signal to client
        await self.send_message(PlaybackStopMessage())
        
        # Transition to listening
        await self.transition_state(SessionState.LISTENING)
        
        # Clear interrupt flag for next interaction
        self.interrupt_event.clear()
        
        logger.info("interrupt_handled", session_id=self.session_id)
    
    def is_interrupted(self) -> bool:
        """Check if session is interrupted."""
        return self.interrupt_event.is_set()
    
    def add_user_turn(self, text: str, language: Language) -> None:
        """Add a user turn to conversation history."""
        turn = ConversationTurn(role="user", content=text, language=language)
        self.conversation_history.turns.append(turn)
        self._trim_history()
    
    def add_assistant_turn(self, text: str, language: Language) -> None:
        """Add an assistant turn to conversation history."""
        turn = ConversationTurn(role="assistant", content=text, language=language)
        self.conversation_history.turns.append(turn)
        self._trim_history()
    
    def _trim_history(self) -> None:
        """Trim conversation history to stay within limits."""
        max_turns = config.memory.max_turns
        if len(self.conversation_history.turns) > max_turns:
            # Keep only the most recent turns
            self.conversation_history.turns = self.conversation_history.turns[-max_turns:]
    
    def get_conversation_context(self) -> str:
        """Get formatted conversation context for LLM."""
        context_parts = []
        
        for turn in self.conversation_history.turns:
            role = "User" if turn.role == "user" else "Assistant"
            context_parts.append(f"{role}: {turn.content}")
        
        return "\n".join(context_parts)
    
    def is_expired(self) -> bool:
        """Check if session has expired due to inactivity."""
        timeout = config.performance.session_timeout
        elapsed = (datetime.now() - self.last_activity).total_seconds()
        return elapsed > timeout
    
    def update_activity(self) -> None:
        """Update last activity timestamp."""
        self.last_activity = datetime.now()


class SessionManager:
    """
    Manages all active sessions.
    """
    
    def __init__(self):
        self.sessions: Dict[str, Session] = {}
        self.max_sessions = config.performance.max_sessions
        self._cleanup_task: Optional[asyncio.Task] = None
        self.logger = get_logger("session_manager")
    
    async def start(self) -> None:
        """Start the session manager."""
        self._cleanup_task = asyncio.create_task(self._cleanup_loop())
        self.logger.info("session_manager_started")
    
    async def stop(self) -> None:
        """Stop the session manager and cleanup all sessions."""
        if self._cleanup_task:
            self._cleanup_task.cancel()
            try:
                await self._cleanup_task
            except asyncio.CancelledError:
                pass
        
        # Close all sessions
        for session_id in list(self.sessions.keys()):
            await self.remove_session(session_id)
        
        self.logger.info("session_manager_stopped")
    
    async def create_session(
        self, 
        websocket: WebSocket, 
        session_id: Optional[str] = None,
        session_config: Optional[SessionConfig] = None
    ) -> Session:
        """Create a new session."""
        if len(self.sessions) >= self.max_sessions:
            raise RuntimeError(f"Maximum sessions ({self.max_sessions}) reached")
        
        session_id = session_id or str(uuid.uuid4())
        session_config = session_config or SessionConfig()
        
        session = Session(
            session_id=session_id,
            websocket=websocket,
            config=session_config
        )
        
        self.sessions[session_id] = session
        
        self.logger.info(
            "session_created",
            session_id=session_id,
            total_sessions=len(self.sessions)
        )
        
        # Send acknowledgment
        await session.send_message(SessionAckMessage(session_id=session_id))
        
        return session
    
    def get_session(self, session_id: str) -> Optional[Session]:
        """Get a session by ID."""
        return self.sessions.get(session_id)
    
    async def remove_session(self, session_id: str) -> None:
        """Remove and cleanup a session."""
        session = self.sessions.pop(session_id, None)
        if session:
            # Cancel any active tasks
            await session.handle_interrupt()
            session.state = SessionState.CLOSED
            
            self.logger.info(
                "session_removed",
                session_id=session_id,
                total_sessions=len(self.sessions)
            )
    
    def get_active_sessions(self) -> list[Dict[str, Any]]:
        """Get list of active sessions info."""
        return [
            {
                "session_id": s.session_id,
                "state": s.state.value,
                "created_at": s.created_at.isoformat(),
                "last_activity": s.last_activity.isoformat(),
                "language": s.detected_language.value
            }
            for s in self.sessions.values()
        ]
    
    async def _cleanup_loop(self) -> None:
        """Periodically cleanup expired sessions."""
        while True:
            try:
                await asyncio.sleep(60)  # Check every minute
                await self._cleanup_expired_sessions()
            except asyncio.CancelledError:
                break
            except Exception as e:
                self.logger.error("cleanup_error", error=str(e))
    
    async def _cleanup_expired_sessions(self) -> None:
        """Remove expired sessions."""
        expired = [
            session_id 
            for session_id, session in self.sessions.items() 
            if session.is_expired()
        ]
        
        for session_id in expired:
            self.logger.info("session_expired", session_id=session_id)
            await self.remove_session(session_id)


# Global session manager instance
session_manager = SessionManager()
