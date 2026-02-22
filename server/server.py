"""
Zeni Voice AI Server
Main FastAPI application with WebSocket support for real-time voice interaction.
"""

import asyncio
import base64
import json
import time
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from core.config import config, env_settings
from core.logging import setup_logging, get_logger, asr_latency, llm_latency, tts_latency, pipeline_latency
from core.protocol import (
    MessageType, SessionState, Language,
    parse_client_message, SessionStartMessage, AudioFrameMessage,
    InterruptMessage, HeartbeatMessage, SessionEndMessage,
    ErrorMessage, HeartbeatAckMessage, TranscriptFinalMessage
)
from core.session import session_manager, Session
from core.pipeline import pipeline_manager, StreamingPipeline

# Admin module
from admin import admin_router

# Setup logging
setup_logging(env_settings.log_level)
logger = get_logger("server")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan manager."""
    logger.info("zeni_server_starting", port=config.server.port)
    
    # Initialize components
    await session_manager.start()
    await pipeline_manager.initialize()
    
    # Initialize vision engine (non-blocking, optional)
    try:
        from engines.vision import initialize_vision
        await initialize_vision()
    except Exception as e:
        logger.warning("vision_init_failed", error=str(e))
    
    logger.info("zeni_server_ready")
    
    yield
    
    # Cleanup
    logger.info("zeni_server_shutting_down")
    await session_manager.stop()
    await pipeline_manager.shutdown()
    logger.info("zeni_server_stopped")


# Create FastAPI app
app = FastAPI(
    title="Zeni Voice AI",
    description="Real-time voice AI assistant with streaming support",
    version="1.0.0",
    lifespan=lifespan
)

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include admin router
app.include_router(admin_router)


# ============== HTTP Endpoints ==============

@app.get("/health")
async def health_check():
    """Health check endpoint."""
    pipeline_health = await pipeline_manager.health_check()
    
    status = "healthy" if all(pipeline_health.values()) else "degraded"
    
    return {
        "status": status,
        "components": pipeline_health,
        "active_sessions": len(session_manager.sessions)
    }


@app.get("/debug/image")
async def debug_get_image():
    """Debug endpoint to view current captured image."""
    from fastapi.responses import Response
    try:
        from engines.vision import get_vision_engine
        vision = get_vision_engine()
        
        if vision._current_image:
            session_id, image_base64, timestamp = vision._current_image
            age_seconds = time.time() - timestamp
            
            # Return image info and optionally the image itself
            import base64
            image_bytes = base64.b64decode(image_base64)
            
            return Response(
                content=image_bytes,
                media_type="image/jpeg",
                headers={
                    "X-Session-ID": session_id[:8],
                    "X-Age-Seconds": str(int(age_seconds)),
                    "X-Image-Size": str(len(image_base64)),
                    "X-Pre-Analysis": vision._pre_analysis_cache[1][:200] if vision._pre_analysis_cache else "none"
                }
            )
        else:
            return JSONResponse({"error": "No image captured yet"}, status_code=404)
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


@app.get("/debug/image-info")
async def debug_image_info():
    """Debug endpoint to get image analysis info."""
    try:
        from engines.vision import get_vision_engine
        vision = get_vision_engine()
        
        result = {
            "has_image": vision._current_image is not None,
            "has_pre_analysis": vision._pre_analysis_cache is not None
        }
        
        if vision._current_image:
            session_id, image_base64, timestamp = vision._current_image
            result["image"] = {
                "session_id": session_id[:8],
                "size_bytes": len(image_base64),
                "age_seconds": int(time.time() - timestamp)
            }
        
        if vision._pre_analysis_cache:
            session_id, analysis, timestamp = vision._pre_analysis_cache
            result["pre_analysis"] = {
                "session_id": session_id[:8],
                "result": analysis,
                "age_seconds": int(time.time() - timestamp)
            }
        
        return result
    except Exception as e:
        return JSONResponse({"error": str(e)}, status_code=500)


@app.get("/sessions")
async def list_sessions():
    """List active sessions."""
    return {
        "sessions": session_manager.get_active_sessions(),
        "total": len(session_manager.sessions),
        "max": config.performance.max_sessions
    }


@app.get("/metrics")
async def get_metrics():
    """Get performance metrics."""
    return {
        "latency": {
            "asr": asr_latency.get_stats(),
            "llm": llm_latency.get_stats(),
            "tts": tts_latency.get_stats(),
            "pipeline": pipeline_latency.get_stats()
        },
        "sessions": {
            "active": len(session_manager.sessions),
            "max": config.performance.max_sessions
        }
    }


# ============== Public API for Android App ==============

from pathlib import Path
from fastapi.responses import FileResponse

PLACEMENT_DIR = Path(__file__).parent.parent / "Placement"

@app.get("/api/placements")
async def get_public_placements():
    """
    Public API for Android app to get placement photos.
    No authentication required.
    """
    if not PLACEMENT_DIR.exists():
        return {"success": True, "photos": [], "total": 0}
    
    photos = []
    for file in PLACEMENT_DIR.iterdir():
        if file.is_file() and file.suffix.lower() in ['.jpg', '.jpeg', '.png', '.gif', '.webp']:
            photos.append({
                "name": file.name,
                "url": f"/api/placements/{file.name}"
            })
    
    return {"success": True, "photos": photos, "total": len(photos)}


@app.get("/api/placements/{filename}")
async def get_public_placement_file(filename: str):
    """Serve placement photo for Android app (public access)."""
    file_path = PLACEMENT_DIR / filename
    if not file_path.exists():
        from fastapi import HTTPException
        raise HTTPException(status_code=404, detail="File not found")
    
    return FileResponse(file_path)


# ============== Personality Settings API ==============

from core.protocol import Personality
import json

SETTINGS_FILE = Path(__file__).parent / "data" / "settings.json"

def load_settings() -> dict:
    """Load global settings from file."""
    if SETTINGS_FILE.exists():
        try:
            return json.loads(SETTINGS_FILE.read_text())
        except:
            pass
    return {"personality": "assistant"}

def save_settings(settings: dict):
    """Save global settings to file."""
    SETTINGS_FILE.parent.mkdir(parents=True, exist_ok=True)
    SETTINGS_FILE.write_text(json.dumps(settings, indent=2))


@app.get("/api/settings")
async def get_settings():
    """Get global settings (personality mode, etc.)."""
    settings = load_settings()
    return {
        "success": True,
        "personality": settings.get("personality", "assistant")
    }


@app.post("/api/settings/personality")
async def set_personality(request_body: dict):
    """
    Set global personality mode.
    This is public API - both Android app and admin dashboard can call it.
    """
    personality = request_body.get("personality", "assistant")
    
    # Validate - now includes "general" mode
    if personality not in ["assistant", "human", "general"]:
        raise HTTPException(status_code=400, detail="Invalid personality. Use 'assistant', 'human', or 'general'")
    
    # Save
    settings = load_settings()
    settings["personality"] = personality
    save_settings(settings)
    
    # Update ALL active sessions immediately!
    if personality == "human":
        personality_enum = Personality.HUMAN
    elif personality == "general":
        personality_enum = Personality.GENERAL
    else:
        personality_enum = Personality.ASSISTANT
    
    updated_count = 0
    for session in session_manager.sessions.values():
        session.config.personality = personality_enum
        updated_count += 1
    
    logger.info("personality_setting_changed", 
               new_personality=personality,
               sessions_updated=updated_count)
    
    return {
        "success": True,
        "personality": personality,
        "sessions_updated": updated_count,
        "message": f"Personality set to {personality} for {updated_count} active session(s)"
    }


# ============== WebSocket Handler ==============

class VoiceSessionHandler:
    """
    Handles a single WebSocket voice session.
    Manages the complete lifecycle from connection to disconnection.
    """
    
    def __init__(self, websocket: WebSocket):
        self.websocket = websocket
        self.session: Optional[Session] = None
        self.pipeline: Optional[StreamingPipeline] = None
        self._processing_task: Optional[asyncio.Task] = None
        self._queue_checker_task: Optional[asyncio.Task] = None
        self._keepalive_task: Optional[asyncio.Task] = None
        self._is_processing = False  # Flag to prevent concurrent pipeline runs
        self._connection_alive = True
    
    async def handle_connection(self):
        """Main connection handler."""
        await self.websocket.accept()
        logger.info("websocket_connected")
        
        # Start keepalive task to prevent connection timeout
        self._keepalive_task = asyncio.create_task(self._keepalive_loop())
        
        try:
            await self._message_loop()
        except WebSocketDisconnect:
            logger.info("websocket_disconnected", session_id=self.session.session_id if self.session else None)
        except Exception as e:
            logger.error("websocket_error", error=str(e))
        finally:
            self._connection_alive = False
            if self._keepalive_task:
                self._keepalive_task.cancel()
            await self._cleanup()
    
    async def _keepalive_loop(self):
        """
        Send periodic ping messages to keep connection alive.
        Prevents network/proxy timeouts from dropping the connection.
        """
        KEEPALIVE_INTERVAL = 25  # Send ping every 25 seconds
        
        while self._connection_alive:
            try:
                await asyncio.sleep(KEEPALIVE_INTERVAL)
                if self._connection_alive:
                    await self._send_message({"type": "ping", "timestamp": asyncio.get_event_loop().time()})
            except Exception as e:
                logger.debug("keepalive_error", error=str(e))
                break
    
    async def _message_loop(self):
        """
        Main message processing loop.
        
        OPTIMIZED: Handles both text (JSON) and binary (raw audio) frames.
        Binary frames eliminate 33% Base64 overhead + JSON parsing for audio.
        """
        while True:
            try:
                # Receive either text or bytes
                message = await self.websocket.receive()
                
                if message["type"] == "websocket.disconnect":
                    break
                elif message["type"] == "websocket.receive":
                    if "text" in message:
                        # JSON message (control messages)
                        try:
                            data = json.loads(message["text"])
                            await self._handle_message(data)
                        except json.JSONDecodeError as e:
                            logger.warning("invalid_json", error=str(e))
                            await self._send_error(400, "Invalid JSON message")
                    elif "bytes" in message:
                        # Binary audio frame - FAST PATH!
                        await self._handle_binary_audio(message["bytes"])
            except Exception as e:
                if "disconnect" in str(e).lower():
                    break
                logger.error("message_loop_error", error=str(e))
                break
    
    async def _handle_binary_audio(self, audio_bytes: bytes):
        """
        Handle binary audio frame - OPTIMIZED PATH.
        No JSON parsing, no Base64 decoding - raw PCM bytes directly.
        """
        if not self.session or not self.pipeline:
            return
        
        # Update activity
        self.session.update_activity()
        
        # Only process audio in LISTENING or IDLE state
        if self.session.state not in [SessionState.IDLE, SessionState.LISTENING]:
            if self.session.state in [SessionState.GENERATING, SessionState.SPEAKING]:
                # Check for speech to interrupt
                import numpy as np
                audio_array = np.frombuffer(audio_bytes, dtype=np.int16)
                rms_energy = np.sqrt(np.mean(audio_array.astype(np.float32) ** 2))
                
                if rms_energy > 300:
                    logger.info("binary_speech_interrupt", energy=int(rms_energy))
                    await self._handle_interrupt()
            return
        
        # Transition to listening if idle
        if self.session.state == SessionState.IDLE:
            await self.session.transition_state(SessionState.LISTENING)
        
        # Process audio through ASR
        is_final_received = await self.pipeline.process_audio_frame(self.session, audio_bytes)
        
        if is_final_received:
            final_text = self.pipeline.get_pending_final_transcript()
            if final_text and not self._is_processing:
                asyncio.create_task(self._process_final_transcript(final_text))
    
    async def _handle_message(self, data: dict):
        """Route and handle incoming messages."""
        msg_type = data.get("type")
        
        if msg_type == MessageType.SESSION_START:
            await self._handle_session_start(data)
        elif msg_type == MessageType.AUDIO_FRAME:
            await self._handle_audio_frame(data)
        elif msg_type == MessageType.IMAGE_FRAME:
            await self._handle_image_frame(data)
        elif msg_type == MessageType.INTERRUPT:
            await self._handle_interrupt()
        elif msg_type == MessageType.LANGUAGE_CHANGE:
            await self._handle_language_change(data)
        elif msg_type == MessageType.VOICE_CHANGE:
            await self._handle_voice_change(data)
        elif msg_type == MessageType.TTS_PROVIDER_CHANGE:
            await self._handle_tts_provider_change(data)
        elif msg_type == MessageType.TTS_SPEED_CHANGE:
            await self._handle_tts_speed_change(data)
        elif msg_type == MessageType.PERSONALITY_CHANGE:
            await self._handle_personality_change(data)
        elif msg_type == MessageType.SPEECH_FINISHED:
            await self._handle_speech_finished()
        elif msg_type == MessageType.HEARTBEAT:
            await self._handle_heartbeat()
        elif msg_type == "ping":
            # Respond to client ping with pong
            await self._send_message({"type": "pong", "timestamp": data.get("timestamp")})
        elif msg_type == "pong":
            # Client responded to our ping - connection is alive
            pass
        elif msg_type == MessageType.SESSION_END:
            await self._handle_session_end()
        elif msg_type == MessageType.ROBOT_STATUS:
            logger.info("robot_status_message_received", data=data)
            await self._handle_robot_status(data)
        else:
            logger.warning("unknown_message_type", type=msg_type)

    async def _handle_voice_change(self, data: dict):
        """Handle voice change message."""
        if not self.session:
            logger.warning("voice_change_without_session")
            return
        
        try:
            from core.protocol import VoiceChangeMessage
            msg = VoiceChangeMessage(**data)
            
            # Update session voice preference
            self.session.config.voice_preference = msg.voice
            
            logger.info("voice_changed", 
                       session_id=self.session.session_id, 
                       new_voice=msg.voice)
            
        except Exception as e:
            logger.error("voice_change_error", error=str(e))
            await self._send_error(400, f"Invalid voice change: {str(e)}")

    async def _handle_tts_provider_change(self, data: dict):
        """Handle TTS provider change message."""
        if not self.session:
            return
        
        try:
            from core.protocol import TtsProviderChangeMessage
            msg = TtsProviderChangeMessage(**data)
            
            # Update session TTS preference
            self.session.config.tts_provider = msg.provider
            
            logger.info("tts_provider_changed", 
                       session_id=self.session.session_id, 
                       new_provider=msg.provider.value)
            
        except Exception as e:
            logger.error("tts_provider_change_error", error=str(e))
            await self._send_error(400, f"Invalid TTS provider change: {str(e)}")

    async def _handle_tts_speed_change(self, data: dict):
        """Handle TTS speed change message."""
        if not self.session:
            return
        
        try:
            from core.protocol import TtsSpeedChangeMessage
            msg = TtsSpeedChangeMessage(**data)
            
            # Update session speaking rate
            self.session.config.speaking_rate = msg.speed
            
            logger.info("tts_speed_changed", 
                       session_id=self.session.session_id, 
                       new_speed=msg.speed)
            
        except Exception as e:
            logger.error("tts_speed_change_error", error=str(e))
            await self._send_error(400, f"Invalid TTS speed change: {str(e)}")

    async def _handle_personality_change(self, data: dict):
        """Handle personality mode change message."""
        if not self.session:
            return
        
        try:
            from core.protocol import PersonalityChangeMessage
            msg = PersonalityChangeMessage(**data)
            
            # Update session personality
            self.session.config.personality = msg.personality
            
            logger.info("personality_changed", 
                       session_id=self.session.session_id, 
                       new_personality=msg.personality.value)
            
        except Exception as e:
            logger.error("personality_change_error", error=str(e))
            await self._send_error(400, f"Invalid personality change: {str(e)}")

    async def _handle_speech_finished(self):
        """Handle explicit signal that speech has finished."""
        if not self.session or not self.pipeline:
            return
            
        logger.info("speech_finished_received", session_id=self.session.session_id)
        
        # If already processing (callback already triggered pipeline), just return
        if self._is_processing:
            logger.info("speech_finished_but_already_processing", session_id=self.session.session_id)
            return
        
        # Transition to TRANSCRIBING immediately to indicate we are working
        if self.session.state == SessionState.LISTENING:
            await self.session.transition_state(SessionState.TRANSCRIBING)
        
        # Explicitly tell pipeline to finalize
        has_result = await self.pipeline.finalize_speech()
        
        # Check again if processing started during finalize (callback might have fired)
        if self._is_processing:
            logger.info("pipeline_started_during_finalize", session_id=self.session.session_id)
            return
        
        if not has_result:
            # Race condition handling:
            # Sometimes the final transcript comes slightly *after* we ask to finalize,
            # because the ASR stream closure takes a few ms to trigger the final event.
            # We wait briefly and check for a pending final in the pipeline.
            import asyncio
            await asyncio.sleep(0.1) # 100ms wait for stragglers
            
            # Check AGAIN if processing started (callback might have fired during sleep)
            if self._is_processing:
                logger.info("pipeline_started_during_wait", session_id=self.session.session_id)
                return
            
            pending = self.pipeline.get_pending_final_transcript()
            if pending:
                logger.info("found_pending_final_after_wait", text=pending[:50])
                asyncio.create_task(self._process_final_transcript(pending))
                return
        
        # If still no result and not processing, go to IDLE
        if not self._is_processing:
            logger.info("speech_finished_but_no_audio_captured")
            await self.session.transition_state(SessionState.IDLE)

    async def _handle_robot_status(self, data: dict):
        """Handle robot connection status update from client."""
        if not self.session:
            return
        
        connected = data.get("connected", False)
        self.session.robot_connected = connected
        
        logger.info("robot_status_updated", 
                   session_id=self.session.session_id,
                   robot_connected=connected)

    async def _handle_session_start(self, data: dict):
        """Handle session start message."""
        try:
            msg = SessionStartMessage(**data)
            
            # Create session
            self.session = await session_manager.create_session(
                websocket=self.websocket,
                session_id=msg.session_id,
                session_config=msg.config
            )
            
            # IMPORTANT: Load saved personality from server settings (overrides client)
            saved_settings = load_settings()
            saved_personality = saved_settings.get("personality", "assistant")
            if saved_personality == "human":
                self.session.config.personality = Personality.HUMAN
            elif saved_personality == "general":
                self.session.config.personality = Personality.GENERAL
            else:
                self.session.config.personality = Personality.ASSISTANT
            logger.info("session_personality_loaded", 
                       session_id=self.session.session_id,
                       personality=saved_personality)
            
            # Create pipeline for this session
            self.pipeline = pipeline_manager.create_pipeline()
            
            # Register callback for final transcripts - DIRECT TRIGGER!
            if hasattr(self.pipeline.asr, 'set_final_callback'):
                self.pipeline.asr.set_final_callback(self._on_final_transcript)
                logger.info("registered_final_callback", session_id=self.session.session_id)
            
            # Register speculative callback for early LLM execution
            if hasattr(self.pipeline.asr, 'set_speculative_callback'):
                self.pipeline.asr.set_speculative_callback(self._on_speculative_transcript)
                logger.info("registered_speculative_callback", session_id=self.session.session_id)
            
            # Transition to idle state
            await self.session.transition_state(SessionState.IDLE)
            
            logger.info("session_started", session_id=self.session.session_id, personality=saved_personality)
            
        except Exception as e:
            logger.error("session_start_failed", error=str(e))
            await self._send_error(500, f"Failed to start session: {str(e)}")
    
    async def _handle_audio_frame(self, data: dict):
        """Handle incoming audio frame."""
        if not self.session or not self.pipeline:
            await self._send_error(400, "No active session")
            return
        
        try:
            msg = AudioFrameMessage(**data)
            
            # Decode audio data
            audio_bytes = base64.b64decode(msg.data)
            
            # Update activity
            self.session.update_activity()
            
            # Only process audio when in LISTENING or IDLE state
            # Drop frames during TRANSCRIBING, GENERATING, SPEAKING to prevent corruption
            if self.session.state not in [SessionState.IDLE, SessionState.LISTENING]:
                if self.session.state in [SessionState.GENERATING, SessionState.SPEAKING]:
                    # Check if this is actual speech (not silence/noise) before interrupting
                    import numpy as np
                    audio_array = np.frombuffer(audio_bytes, dtype=np.int16)
                    rms_energy = np.sqrt(np.mean(audio_array.astype(np.float32) ** 2))
                    
                    # Only interrupt if energy is above speech threshold (300 is typical for speech)
                    if rms_energy > 300:
                        logger.info("speech_detected_during_response", 
                                  session_id=self.session.session_id, 
                                  energy=int(rms_energy))
                        await self._handle_interrupt()
                return
            
            # Transition to listening if idle
            if self.session.state == SessionState.IDLE:
                await self.session.transition_state(SessionState.LISTENING)
            
            # Process audio through ASR
            is_final_received = await self.pipeline.process_audio_frame(self.session, audio_bytes)
            
            logger.debug("audio_frame_processed", is_final=is_final_received, seq=msg.sequence)
            
            # If final transcript received, immediately trigger pipeline!
            if is_final_received:
                logger.info("is_final_TRUE_getting_transcript", session_id=self.session.session_id)
                final_text = self.pipeline.get_pending_final_transcript()
                logger.info("got_pending_final", text=final_text[:50] if final_text else "None")
                if final_text:
                    logger.info("final_received_triggering_pipeline",
                               session_id=self.session.session_id, 
                               text=final_text[:50])
                    # Don't await - run in background so we can continue processing audio for interrupts
                    if not self._is_processing:
                        asyncio.create_task(self._process_final_transcript(final_text))
                    else:
                        logger.warning("already_processing_cannot_start_new", session_id=self.session.session_id)
            
        except Exception as e:
            logger.error("audio_frame_error", error=str(e))
    
    async def _handle_image_frame(self, data: dict):
        """
        Handle incoming image frame - starts PROACTIVE VISION ANALYSIS.
        Pre-analysis runs in PARALLEL while user is still speaking!
        """
        if not self.session:
            return
        
        try:
            image_data = data.get("data", "")
            if not image_data:
                logger.warning("image_frame_empty", session_id=self.session.session_id[:8])
                return
            
            # PROACTIVE: Start vision analysis immediately in parallel with ASR
            # By the time user finishes speaking, vision analysis may be done!
            from engines.vision import get_vision_engine
            vision = get_vision_engine()
            vision.receive_image_and_preanalyze(self.session.session_id, image_data)
            logger.info("proactive_vision_started", session_id=self.session.session_id[:8])
            
        except Exception as e:
            logger.warning("image_frame_error", error=str(e))
    
    async def _on_final_transcript(self, asr_result: 'ASRResult'):
        """
        Called DIRECTLY by Google ASR when is_final=True arrives.
        NO POLLING, NO DELAYS - immediate callback!
        """
        if not self.session or not self.pipeline:
            return
        
        logger.info("FINAL_CALLBACK_TRIGGERED", 
                   text=asr_result.text[:50], 
                   confidence=asr_result.confidence)
        
        # Send to client
        await self.session.send_message(TranscriptFinalMessage(
            text=asr_result.text,
            confidence=asr_result.confidence,
            language=asr_result.language
        ))
        
        # Immediately trigger pipeline if not already processing
        # We allow LISTENING (normal flow) and IDLE (late arrival after PTT release)
        valid_states = [SessionState.LISTENING, SessionState.TRANSCRIBING, SessionState.IDLE]
        
        if not self._is_processing and self.session.state in valid_states:
            logger.info("triggering_pipeline_from_callback", text=asr_result.text[:50])
            asyncio.create_task(self._process_final_transcript(asr_result.text))
        else:
            logger.warning("cannot_process_final", 
                          is_processing=self._is_processing,
                          state=self.session.state.value)
    
    async def _on_speculative_transcript(self, asr_result: 'ASRResult'):
        """
        Called when ASR detects high-confidence partial transcript.
        Starts RAG search + tool check EARLY while user finishes speaking.
        """
        if not self.session or not self.pipeline:
            return
        
        # Don't speculate if already processing
        if self._is_processing:
            return
        
        logger.info("SPECULATIVE_TRIGGERED", 
                   text=asr_result.text[:50], 
                   confidence=asr_result.confidence)
        
        # Start pre-warming: RAG search in background
        # This result will be reused when final transcript arrives
        try:
            from engines.rag import get_faq_context
            # Pre-compute RAG context (won't block main flow)
            asyncio.create_task(self._precompute_rag(asr_result.text))
        except ImportError:
            pass
    
    async def _precompute_rag(self, text: str):
        """Pre-compute RAG context for speculative execution."""
        try:
            from engines.rag import get_faq_context
            result = get_faq_context(text, top_k=3)
            if result:
                logger.info("rag_precomputed", text=text[:30], result_len=len(result))
        except Exception as e:
            logger.debug("rag_precompute_failed", error=str(e))
    
    async def _check_asr_queue(self):
        """
        Background task that checks ASR queue for final results.
        Needed because: User releases button → audio stops → but Google still
        sends is_final=True to queue → no audio frame to process it!
        """
        logger.info("queue_checker_started")
        
        while True:
            try:
                if not self.session or not self.pipeline:
                    await asyncio.sleep(0.1)
                    continue
                
                # Only check when LISTENING and not processing
                if self.session.state == SessionState.LISTENING and not self._is_processing:
                    # Check if there's a pending final transcript
                    final_text = self.pipeline.get_pending_final_transcript()
                    if final_text:
                        logger.info("queue_checker_found_final", text=final_text[:50])
                        asyncio.create_task(self._process_final_transcript(final_text))
                
                await asyncio.sleep(0.05)  # Check every 50ms
                
            except asyncio.CancelledError:
                logger.info("queue_checker_cancelled")
                break
            except Exception as e:
                logger.error("queue_checker_error", error=str(e))
                await asyncio.sleep(0.1)
    
    async def _process_final_transcript(self, transcript: str):
        """Process a final transcript and run the LLM pipeline."""
        if not self.session or not self.pipeline:
            return
        
        if self._is_processing:
            logger.warning("already_processing_skipping", session_id=self.session.session_id)
            return
        
        self._is_processing = True
        
        try:
            # Transition to transcribing FIRST to stop new audio processing
            await self.session.transition_state(SessionState.TRANSCRIBING)
            
            logger.info(
                "transcript_finalized",
                session_id=self.session.session_id,
                text=transcript[:100]
            )
            
            # Cancel any existing processing task
            if self._processing_task and not self._processing_task.done():
                self._processing_task.cancel()
            
            # Start pipeline processing
            self._processing_task = asyncio.create_task(
                self.pipeline.run_full_pipeline(self.session, transcript)
            )
            
            # Wait for pipeline to complete
            await self._processing_task
            
        except asyncio.CancelledError:
            logger.info("processing_cancelled", session_id=self.session.session_id)
        except Exception as e:
            logger.error("processing_error", session_id=self.session.session_id, error=str(e))
            # Return to idle on error
            if self.session.state != SessionState.CLOSED:
                await self.session.transition_state(SessionState.IDLE)
        finally:
            self._is_processing = False
            # Reset for next utterance
            if self.pipeline:
                await self.pipeline.reset_for_new_utterance(self.session)
    
    async def _handle_interrupt(self):
        """Handle interrupt signal."""
        if not self.session:
            return
        
        logger.info("interrupt_received", session_id=self.session.session_id)
        
        # Cancel processing task
        if self._processing_task and not self._processing_task.done():
            self._processing_task.cancel()
            try:
                await self._processing_task
            except asyncio.CancelledError:
                pass
        
        # Handle interrupt in session
        await self.session.handle_interrupt()
        
        # Reset pipeline
        if self.pipeline:
            await self.pipeline.reset_for_new_utterance(self.session)
        
        # Reset processing flag
        self._is_processing = False
    
    async def _handle_language_change(self, data: dict):
        """Handle language change message."""
        if not self.session:
            logger.warning("language_change_without_session")
            return
        
        try:
            from core.protocol import LanguageChangeMessage
            msg = LanguageChangeMessage(**data)
            
            # Update session language preference
            self.session.config.language_preference = msg.language
            
            logger.info("language_changed", 
                       session_id=self.session.session_id, 
                       new_language=msg.language.value)
            
        except Exception as e:
            logger.error("language_change_error", error=str(e))
            await self._send_error(400, f"Invalid language change: {str(e)}")
    
    async def _handle_heartbeat(self):
        """Handle heartbeat message."""
        await self.websocket.send_json(HeartbeatAckMessage().model_dump())
        
        if self.session:
            self.session.update_activity()
    
    async def _handle_session_end(self):
        """Handle session end message."""
        if self.session:
            logger.info("session_end_requested", session_id=self.session.session_id)
            
            # Clean up vision session data
            try:
                from engines.vision import get_vision_engine
                vision = get_vision_engine()
                if vision._initialized:
                    vision.clear_session(self.session.session_id)
            except Exception:
                pass
            
            await session_manager.remove_session(self.session.session_id)
            self.session = None
    
    async def _send_error(self, code: int, message: str):
        """Send error message to client."""
        await self.websocket.send_json(
            ErrorMessage(code=code, message=message).model_dump()
        )
    
    async def _cleanup(self):
        """Cleanup resources."""
        # Cancel queue checker
        if self._queue_checker_task and not self._queue_checker_task.done():
            self._queue_checker_task.cancel()
            try:
                await self._queue_checker_task
            except asyncio.CancelledError:
                pass
        
        # Cancel processing task
        if self._processing_task and not self._processing_task.done():
            self._processing_task.cancel()
            try:
                await self._processing_task
            except asyncio.CancelledError:
                pass
        
        # Remove session
        if self.session:
            await session_manager.remove_session(self.session.session_id)


@app.websocket("/voice")
async def voice_endpoint(websocket: WebSocket):
    """Main WebSocket endpoint for voice interaction."""
    handler = VoiceSessionHandler(websocket)
    await handler.handle_connection()


# ============== Main Entry Point ==============

def main():
    """Main entry point."""
    import uvicorn
    
    logger.info(
        "starting_zeni_server",
        host=config.server.host,
        port=config.server.port
    )
    
    uvicorn.run(
        "server:app",
        host=config.server.host,
        port=config.server.port,
        loop="uvloop",
        workers=config.server.workers,
        log_level=config.server.log_level.lower()
    )


if __name__ == "__main__":
    main()
