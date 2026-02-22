"""
Zeni Protocol Definitions
Defines all message types and data structures for WebSocket communication.
"""

from enum import Enum
from typing import Optional, Dict, Any, List
from datetime import datetime
from pydantic import BaseModel, Field
import uuid


class MessageType(str, Enum):
    """WebSocket message types."""
    # Session control
    SESSION_START = "session_start"
    SESSION_END = "session_end"
    SESSION_ACK = "session_ack"
    LANGUAGE_CHANGE = "language_change"
    VOICE_CHANGE = "voice_change"
    TTS_PROVIDER_CHANGE = "tts_provider_change"
    TTS_SPEED_CHANGE = "tts_speed_change"
    PERSONALITY_CHANGE = "personality_change"  # Switch between assistant/human personality
    
    # Keepalive
    PING = "ping"
    PONG = "pong"

    # Audio flow
    AUDIO_FRAME = "audio_frame"
    AUDIO_RESPONSE = "audio_response"
    SPEECH_FINISHED = "speech_finished"
    
    # Vision
    IMAGE_FRAME = "image_frame"  # Camera frame for visual context
    REQUEST_IMAGE = "request_image"  # Server requests image capture from client
    
    # Transcript events
    TRANSCRIPT_PARTIAL = "transcript_partial"
    TRANSCRIPT_FINAL = "transcript_final"
    
    # LLM events
    LLM_TOKEN = "llm_token"
    LLM_COMPLETE = "llm_complete"
    
    # Control signals
    INTERRUPT = "interrupt"
    PLAYBACK_STOP = "playback_stop"
    
    # Interactive features
    CAMPUS_TOUR = "campus_tour"  # Trigger 360° campus tour
    FEE_STRUCTURE = "fee_structure"  # Show detailed fee structure
    SHOW_PLACEMENTS = "show_placements"  # Show top placement students
    
    # Robot control
    ROBOT_COMMAND = "robot_command"  # Send movement command to robot
    ROBOT_STATUS = "robot_status"  # Client reports robot connection status
    
    # Status
    ERROR = "error"
    HEARTBEAT = "heartbeat"
    HEARTBEAT_ACK = "heartbeat_ack"
    STATE_CHANGE = "state_change"


class SessionState(str, Enum):
    """Session state machine states."""
    IDLE = "idle"
    LISTENING = "listening"
    TRANSCRIBING = "transcribing"
    GENERATING = "generating"
    SPEAKING = "speaking"
    INTERRUPTED = "interrupted"
    ERROR = "error"
    CLOSED = "closed"


class Language(str, Enum):
    """Supported languages."""
    ENGLISH = "en"
    HINDI = "hi"
    AUTO = "auto"


class TTSProvider(str, Enum):
    """TTS Service Provider - Google Cloud TTS only."""
    GOOGLE = "google"


class Personality(str, Enum):
    """AI Personality modes."""
    ASSISTANT = "assistant"  # Professional college bot with RAG
    HUMAN = "human"  # Friendly college bot with RAG
    GENERAL = "general"  # Free AI chat - no RAG, no college data, just talk


# ============== Inbound Messages (Client -> Server) ==============

class SessionConfig(BaseModel):
    """Session configuration from client."""
    sample_rate: int = 16000
    language_preference: Language = Language.AUTO
    voice_preference: str = "Kore"  # Female voice
    tts_provider: TTSProvider = TTSProvider.GOOGLE
    speaking_rate: float = 1.0
    push_to_talk: bool = False
    personality: Personality = Personality.ASSISTANT  # AI personality mode


class SessionStartMessage(BaseModel):
    """Session start message."""
    type: MessageType = MessageType.SESSION_START
    session_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    config: SessionConfig = Field(default_factory=SessionConfig)
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class AudioFrameMessage(BaseModel):
    """Audio frame from client."""
    type: MessageType = MessageType.AUDIO_FRAME
    timestamp: int
    data: str  # Base64 encoded PCM data
    sequence: int


class InterruptMessage(BaseModel):
    """Interrupt signal from client."""
    type: MessageType = MessageType.INTERRUPT
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class HeartbeatMessage(BaseModel):
    """Heartbeat message."""
    type: MessageType = MessageType.HEARTBEAT
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class LanguageChangeMessage(BaseModel):
    """Language change message."""
    type: MessageType = MessageType.LANGUAGE_CHANGE
    language: Language
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class VoiceChangeMessage(BaseModel):
    type: MessageType = MessageType.VOICE_CHANGE
    voice: str
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class TtsProviderChangeMessage(BaseModel):
    type: MessageType = MessageType.TTS_PROVIDER_CHANGE
    provider: TTSProvider
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class TtsSpeedChangeMessage(BaseModel):
    type: MessageType = MessageType.TTS_SPEED_CHANGE
    speed: float
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class PersonalityChangeMessage(BaseModel):
    """Personality mode change message."""
    type: MessageType = MessageType.PERSONALITY_CHANGE
    personality: Personality
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class SessionEndMessage(BaseModel):
    """Session end message."""
    type: MessageType = MessageType.SESSION_END
    session_id: str
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class SpeechFinishedMessage(BaseModel):
    """Signal from client that speech has ended (e.g. PTT button released)."""
    type: MessageType = MessageType.SPEECH_FINISHED
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


# ============== Outbound Messages (Server -> Client) ==============

class SessionAckMessage(BaseModel):
    """Session acknowledgment."""
    type: MessageType = MessageType.SESSION_ACK
    session_id: str
    status: str = "connected"
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class AudioResponseMessage(BaseModel):
    """Audio response chunk to client."""
    type: MessageType = MessageType.AUDIO_RESPONSE
    sequence: int
    data: str  # Base64 encoded PCM data
    final: bool = False
    sample_rate: int = 24000


class TranscriptPartialMessage(BaseModel):
    """Partial transcript update."""
    type: MessageType = MessageType.TRANSCRIPT_PARTIAL
    text: str
    language: Language
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class TranscriptFinalMessage(BaseModel):
    """Final transcript."""
    type: MessageType = MessageType.TRANSCRIPT_FINAL
    text: str
    confidence: float
    language: Language
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class LLMTokenMessage(BaseModel):
    """LLM token stream."""
    type: MessageType = MessageType.LLM_TOKEN
    token: str
    sequence: int


class LLMCompleteMessage(BaseModel):
    """LLM complete response."""
    type: MessageType = MessageType.LLM_COMPLETE
    full_text: str
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class PlaybackStopMessage(BaseModel):
    """Stop playback signal."""
    type: MessageType = MessageType.PLAYBACK_STOP
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class StateChangeMessage(BaseModel):
    """State change notification."""
    type: MessageType = MessageType.STATE_CHANGE
    state: SessionState
    previous_state: Optional[SessionState] = None
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class ErrorMessage(BaseModel):
    """Error message."""
    type: MessageType = MessageType.ERROR
    code: int
    message: str
    details: Optional[Dict[str, Any]] = None
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class HeartbeatAckMessage(BaseModel):
    """Heartbeat acknowledgment."""
    type: MessageType = MessageType.HEARTBEAT_ACK
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class CampusTourMessage(BaseModel):
    """Campus tour trigger - opens Matterport 360° view in Android app."""
    type: MessageType = MessageType.CAMPUS_TOUR
    tour_id: str  # Unique tour identifier
    name: str  # Human-readable name (e.g., "Seminar Hall")
    url: str  # Matterport URL
    description: str  # Brief description
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class FeeStructureMessage(BaseModel):
    """Fee structure trigger - opens WebView with fee details in Android app."""
    type: MessageType = MessageType.FEE_STRUCTURE
    program_id: str  # Unique program identifier (e.g., "btech-cse")
    program_name: str  # Human-readable name (e.g., "B.Tech Computer Science & Engineering")
    url: str  # Fee structure URL
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


class PlacementMessage(BaseModel):
    """Placement gallery trigger - opens full-screen placement photo viewer in Android app."""
    type: MessageType = MessageType.SHOW_PLACEMENTS
    title: str  # Title for the placement gallery (e.g., "Top Placements - GEHU Bhimtal")
    timestamp: int = Field(default_factory=lambda: int(datetime.now().timestamp() * 1000))


# ============== Conversation Memory ==============

class ConversationTurn(BaseModel):
    """A single turn in conversation."""
    role: str  # "user" or "assistant"
    content: str
    language: Language = Language.ENGLISH
    timestamp: datetime = Field(default_factory=datetime.now)


class ConversationHistory(BaseModel):
    """Conversation history for a session."""
    turns: List[ConversationTurn] = Field(default_factory=list)
    summary: Optional[str] = None
    total_tokens: int = 0


# ============== Message Parsing ==============

def parse_client_message(data: Dict[str, Any]) -> Optional[BaseModel]:
    """Parse incoming client message based on type."""
    msg_type = data.get("type")
    
    parsers = {
        MessageType.SESSION_START: SessionStartMessage,
        MessageType.SESSION_END: SessionEndMessage,
        MessageType.AUDIO_FRAME: AudioFrameMessage,
        MessageType.SPEECH_FINISHED: SpeechFinishedMessage,
        MessageType.INTERRUPT: InterruptMessage,
        MessageType.HEARTBEAT: HeartbeatMessage,
        MessageType.LANGUAGE_CHANGE: LanguageChangeMessage,
        MessageType.VOICE_CHANGE: VoiceChangeMessage,
        MessageType.TTS_PROVIDER_CHANGE: TtsProviderChangeMessage,
        MessageType.TTS_SPEED_CHANGE: TtsSpeedChangeMessage,
    }
    
    parser = parsers.get(msg_type)
    if parser:
        return parser(**data)
    return None
