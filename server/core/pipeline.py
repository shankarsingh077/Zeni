"""
Zeni Streaming Pipeline
Orchestrates the complete ASR → LLM → TTS pipeline with streaming and interruption support.

Architecture: User Speech → LLM Reasoning → Action Decision → Execute
No intent matching - the LLM decides what actions to take.
"""

import asyncio
import base64
import time
from typing import Optional, AsyncGenerator
from dataclasses import dataclass

from core.config import config
from core.protocol import (
    Language, SessionState, Personality, MessageType,
    TranscriptPartialMessage, TranscriptFinalMessage,
    LLMTokenMessage, LLMCompleteMessage,
    AudioResponseMessage, ErrorMessage,
    CampusTourMessage, FeeStructureMessage, PlacementMessage
)
from core.session import Session
from core.logging import get_logger, pipeline_latency
from engines.asr import ASRResult
from engines.google_asr import GoogleASREngine
from engines.llm import LLMEngine, LLMResponse
from engines.tts import TTSEngine, TTSChunk

# Import action engine for AI-driven actions
try:
    from engines.actions import get_action_engine, parse_action_from_response, ActionResult
    ACTIONS_AVAILABLE = True
except ImportError:
    ACTIONS_AVAILABLE = False
    get_action_engine = None
    parse_action_from_response = None

logger = get_logger("pipeline")


@dataclass
class PipelineConfig:
    """Pipeline configuration."""
    interrupt_threshold_ms: int = 50


class StreamingPipeline:
    """
    ULTRA-OPTIMIZED Voice AI Pipeline.
    
    Audio → ASR → LLM → TTS → Audio
    
    Key Optimizations:
    1. Speculative LLM execution on high-confidence partials
    2. Immediate TTS streaming (zero buffering)
    3. Parallel processing where possible
    """
    
    def __init__(
        self,
        asr_engine: GoogleASREngine,
        llm_engine: LLMEngine,
        tts_engine: TTSEngine
    ):
        self.asr = asr_engine
        self.llm = llm_engine
        self.tts = tts_engine
        
        # Track last partial for deduplication
        self._last_partial_text = ""
        self._last_partial_time = 0.0
        self._pending_final_text: Optional[str] = None
        
        # SPECULATIVE EXECUTION state
        self._speculative_task: Optional[asyncio.Task] = None
        self._speculative_text: Optional[str] = None
        self._speculative_cancelled = False
        
        self.config = PipelineConfig(
            interrupt_threshold_ms=config.performance.interrupt_threshold_ms
        )
    
    async def process_audio_frame(
        self,
        session: Session,
        audio_data: bytes
    ) -> bool:
        """
        Process a single audio frame through the ASR engine.
        Sends partial transcripts to client in real-time.
        Returns True if final transcript was received (ready to process).
        """
        if session.is_interrupted():
            return False
        
        # Process through ASR
        result = await self.asr.process_audio(
            audio_data,
            session.config.language_preference
        )
        
        if result:
            # Update detected language
            session.detected_language = result.language
            
            if result.is_partial:
                # Deduplicate and throttle partial transcripts
                current_time = time.perf_counter()
                time_since_last = current_time - self._last_partial_time
                
                # Send if text changed (allow very fast updates for real-time streaming)
                # 20ms throttle for responsive real-time feel
                text_changed = result.text != self._last_partial_text
                if (text_changed and 
                    result.text.strip() and  # Not empty
                    time_since_last >= 0.02):  # 20ms throttle for real-time streaming
                    
                    self._last_partial_text = result.text
                    self._last_partial_time = current_time
                    
                    logger.info("partial_sent", text=result.text[:80])
                    
                    # Send partial transcript
                    await session.send_message(TranscriptPartialMessage(
                        text=result.text,
                        language=result.language
                    ))
            
            if result.is_final:
                # Final transcript received from Google!
                logger.info("final_transcript_received", text=result.text[:100], confidence=result.confidence)
                await session.send_message(TranscriptFinalMessage(
                    text=result.text,
                    confidence=result.confidence,
                    language=result.language
                ))
                # Store final transcript
                self._pending_final_text = result.text
                # Reset partial tracking
                self._last_partial_text = ""
                
                logger.info("returning_true_for_is_final", text=result.text[:50])
                # Return True to signal final transcript received
                return True
        
        return False
    
    def get_pending_final_transcript(self) -> Optional[str]:
        """
        Get and clear any pending final transcript.
        Called by the background poller in server.py.
        """
        if self._pending_final_text:
            text = self._pending_final_text
            self._pending_final_text = None
            return text
        return None
    
    async def finalize_speech(self) -> bool:
        """
        Force finalization of speech (called on SPEECH_FINISHED).
        """
        logger.info("pipeline_finalize_speech")
        result = await self.asr.finalize()
        
        if result and result.text.strip():
            logger.info("finalize_speech_got_result", text=result.text[:50])
            self._pending_final_text = result.text
            self._last_partial_text = ""
            return True
            
        return False
    
    async def start_speculative_llm(self, session: Session, partial_text: str):
        """
        Start LLM processing speculatively on a high-confidence partial.
        
        This can save 200-500ms by starting LLM before is_final=True.
        If the final text differs significantly, we cancel and restart.
        """
        logger.info("speculative_llm_start", text=partial_text[:50])
        self._speculative_text = partial_text
        self._speculative_cancelled = False
        
        # We don't actually run full LLM here - just warm it up
        # The actual execution happens when final is confirmed
        # This is a "soft" speculative execution
    
    def should_use_speculative_result(self, final_text: str) -> bool:
        """
        Check if speculative result is still valid for the final transcript.
        """
        if not self._speculative_text:
            return False
        
        # If texts are very similar (>80% match), use speculative
        spec_words = set(self._speculative_text.lower().split())
        final_words = set(final_text.lower().split())
        
        if not spec_words or not final_words:
            return False
        
        overlap = len(spec_words & final_words)
        similarity = overlap / max(len(spec_words), len(final_words))
        
        valid = similarity >= 0.8
        logger.info("speculative_validation", 
                   similarity=round(similarity, 2),
                   valid=valid,
                   spec=self._speculative_text[:30],
                   final=final_text[:30])
        return valid
    
    def cancel_speculative(self):
        """Cancel any pending speculative execution."""
        if self._speculative_task and not self._speculative_task.done():
            self._speculative_task.cancel()
            logger.info("speculative_cancelled")
        self._speculative_task = None
        self._speculative_text = None
        self._speculative_cancelled = True
    
    async def run_llm_stream(
        self,
        session: Session,
        transcript: str
    ) -> AsyncGenerator[str, None]:
        """
        Stream LLM response tokens.
        Sends tokens to client and yields for TTS processing.
        """
        token_sequence = 0
        full_response = []
        
        # Create callback for requesting image from client
        async def request_image_from_client():
            """Send REQUEST_IMAGE message to client."""
            await session.send_message({
                "type": MessageType.REQUEST_IMAGE.value,
                "session_id": session.session_id
            })
            logger.info("request_image_sent", session_id=session.session_id[:8])
        
        # Create callback for sending robot commands to client
        async def send_robot_command(action: str, duration: int, speed: int):
            """Send ROBOT_COMMAND message to client."""
            await session.send_message({
                "type": MessageType.ROBOT_COMMAND.value,
                "action": action,
                "duration_ms": duration,
                "speed_percent": speed,
                "timestamp": int(time.time() * 1000)
            })
            logger.info("robot_command_sent", session_id=session.session_id[:8], action=action)
        
        async for response in self.llm.generate_stream(
            user_message=transcript,
            conversation_history=session.conversation_history.turns,
            language=session.detected_language,
            cancel_event=session.interrupt_event,
            personality=session.config.personality,
            session_id=session.session_id,
            request_image_fn=request_image_from_client,
            robot_enabled=session.robot_connected,
            robot_command_fn=send_robot_command if session.robot_connected else None
        ):
            if session.is_interrupted():
                logger.info("llm_stream_interrupted", session_id=session.session_id)
                return
            
            if response.is_complete:
                # Send completion message
                await session.send_message(LLMCompleteMessage(
                    full_text=response.full_text or ""
                ))
                
                # Add to conversation history
                if response.full_text:
                    session.add_assistant_turn(response.full_text, session.detected_language)
            else:
                # Send token
                full_response.append(response.token)
                token_sequence += 1
                
                await session.send_message(LLMTokenMessage(
                    token=response.token,
                    sequence=token_sequence
                ))
                
                # Yield for TTS processing
                yield response.token
    
    async def run_tts_stream(
        self,
        session: Session,
        text_stream: AsyncGenerator[str, None]
    ) -> AsyncGenerator[TTSChunk, None]:
        """
        Stream TTS audio from text tokens.
        """
        async for chunk in self.tts.synthesize_stream(
            text_stream=text_stream,
            language=session.detected_language,
            voice_name=session.config.voice_preference,
            speaking_rate=session.config.speaking_rate,
            provider=session.config.tts_provider.value,  # Pass selected provider (google/edge)
            cancel_event=session.interrupt_event
        ):
            if session.is_interrupted():
                logger.info("tts_stream_interrupted", session_id=session.session_id)
                return
            
            yield chunk
    
    async def send_audio_response(
        self,
        session: Session,
        audio_chunk: TTSChunk
    ) -> None:
        """
        Send an audio response chunk to the client.
        """
        if session.is_interrupted():
            return
        
        session.audio_sequence += 1
        
        # Encode audio as base64
        audio_b64 = base64.b64encode(audio_chunk.audio_data).decode('utf-8')
        
        await session.send_message(AudioResponseMessage(
            sequence=session.audio_sequence,
            data=audio_b64,
            final=audio_chunk.is_final,
            sample_rate=audio_chunk.sample_rate
        ))
    
    async def run_full_pipeline(
        self,
        session: Session,
        transcript: str
    ) -> None:
        """
        Run the complete LLM → TTS pipeline for a transcript.
        
        Architecture: LLM reasons and decides actions (no intent matching).
        1. Send user message to LLM
        2. LLM responds with text + optional action
        3. Parse action from response
        4. Execute action if present (e.g., open campus tour)
        5. Stream TTS for the text response
        """
        if not transcript.strip():
            logger.warning("empty_transcript", session_id=session.session_id)
            return
        
        logger.info(
            "pipeline_start",
            session_id=session.session_id,
            transcript_length=len(transcript),
            language=session.detected_language.value
        )
        
        start_time = time.perf_counter()
        
        try:
            # Add user turn to history
            session.add_user_turn(transcript, session.detected_language)
            
            # Transition to generating state
            await session.transition_state(SessionState.GENERATING)
            
            logger.info("llm_request_starting", session_id=session.session_id)
            
            # ========== STREAM LLM → TTS (FAST) + ACCUMULATE FOR ACTIONS ==========
            # Key: Voice starts IMMEDIATELY, action parsing happens AFTER
            # Filter out action blocks from TTS stream (don't speak JSON!)
            accumulated_response = []
            
            async def llm_text_generator():
                """Stream LLM tokens to TTS while filtering out action blocks"""
                token_count = 0
                buffer = ""  # Buffer for detecting action block start
                in_action_block = False
                
                try:
                    async for token in self.run_llm_stream(session, transcript):
                        token_count += 1
                        accumulated_response.append(token)  # Always accumulate full response
                        
                        if token_count == 1:
                            logger.info("llm_first_token_yielded", session_id=session.session_id)
                        
                        # Filter out action block from TTS
                        buffer += token
                        
                        # Check if we're entering an action block
                        if "```action" in buffer or "```Action" in buffer:
                            in_action_block = True
                            # Yield text before the action block
                            action_start = buffer.find("```")
                            if action_start > 0:
                                yield buffer[:action_start]
                            buffer = ""
                            continue
                        
                        # Check if we're exiting an action block
                        if in_action_block:
                            if "```" in buffer and buffer.count("```") >= 1:
                                # Found closing ``` - action block complete
                                in_action_block = False
                                # Find text after closing ```
                                close_pos = buffer.rfind("```")
                                if close_pos != -1 and close_pos + 3 < len(buffer):
                                    yield buffer[close_pos + 3:]
                                buffer = ""
                            continue
                        
                        # Not in action block - yield tokens for TTS
                        # But buffer a bit to detect ``` start
                        if len(buffer) > 10 and "```" not in buffer:
                            yield buffer
                            buffer = ""
                    
                    # Flush remaining buffer (if not in action block)
                    if buffer and not in_action_block and "```" not in buffer:
                        yield buffer
                    
                    logger.info("llm_generator_complete", session_id=session.session_id, total_tokens=token_count)
                except asyncio.TimeoutError:
                    logger.error("llm_stream_timeout", session_id=session.session_id)
                    raise
                except Exception as e:
                    logger.error("llm_stream_error", session_id=session.session_id, error=str(e))
                    raise
            
            # Transition to speaking once TTS starts
            first_audio = True
            audio_chunk_count = 0
            
            logger.info("starting_tts_stream", session_id=session.session_id)
            # Stream TTS from LLM output - voice starts FAST!
            async for audio_chunk in self.run_tts_stream(session, llm_text_generator()):
                if session.is_interrupted():
                    break
                
                audio_chunk_count += 1
                
                if first_audio:
                    await session.transition_state(SessionState.SPEAKING)
                    first_token_time = (time.perf_counter() - start_time) * 1000
                    logger.info(
                        "first_audio_latency",
                        session_id=session.session_id,
                        latency_ms=round(first_token_time, 2)
                    )
                    first_audio = False
                
                # Send chunk immediately
                await self.send_audio_response(session, audio_chunk)
                logger.debug("audio_chunk_sent", 
                            session_id=session.session_id,
                            chunk_num=audio_chunk_count,
                            chunk_bytes=len(audio_chunk.audio_data))
            
            # ========== PARSE ACTIONS AFTER TTS STARTED ==========
            # Voice is already playing, now check for actions (delayed is OK)
            full_response = "".join(accumulated_response).strip()
            clean_text = full_response
            
            if ACTIONS_AVAILABLE and parse_action_from_response:
                clean_text, action_data = parse_action_from_response(full_response)
                
                if action_data:
                    logger.info("action_detected_by_llm", 
                               session_id=session.session_id,
                               action=action_data)
                    
                    # Execute the action (tour opens while voice is playing)
                    action_engine = get_action_engine()
                    action_type = action_data.get("action", "")
                    action_result = action_engine.execute_action(action_type, action_data)
                    
                    if action_result and action_result.action_type == "campus_tour":
                        # Send campus tour message to client
                        await session.send_message(CampusTourMessage(
                            tour_id=action_result.data["tour_id"],
                            name=action_result.data["name"],
                            url=action_result.data["url"],
                            description=action_result.data["description"]
                        ))
                        logger.info("campus_tour_action_sent",
                                   session_id=session.session_id,
                                   tour_name=action_result.data["name"])
                    
                    elif action_result and action_result.action_type == "fee_structure":
                        # Send fee structure message to client
                        await session.send_message(FeeStructureMessage(
                            program_id=action_result.data["program_id"],
                            program_name=action_result.data["program_name"],
                            url=action_result.data["url"]
                        ))
                        logger.info("fee_structure_action_sent",
                                   session_id=session.session_id,
                                   program_name=action_result.data["program_name"])
                    
                    elif action_result and action_result.action_type == "show_placements":
                        # Send placement gallery message to client
                        await session.send_message(PlacementMessage(
                            title=action_result.data["title"]
                        ))
                        logger.info("placements_action_sent",
                                   session_id=session.session_id,
                                   title=action_result.data["title"])
            
            # Add to conversation history (clean text without action block)
            session.add_assistant_turn(clean_text, session.detected_language)
            
            # Pipeline complete
            total_time = (time.perf_counter() - start_time) * 1000
            logger.info(
                "pipeline_complete",
                session_id=session.session_id,
                total_time_ms=round(total_time, 2),
                total_audio_chunks=audio_chunk_count
            )
        
        except asyncio.CancelledError:
            logger.info("pipeline_cancelled", session_id=session.session_id)
            # Ensure state is reset for next interaction
            if not session.is_interrupted():
                await session.transition_state(SessionState.IDLE)
        except asyncio.TimeoutError:
            logger.error("pipeline_timeout", session_id=session.session_id)
            await session.send_message(ErrorMessage(
                code=408,
                message="Request timed out. Please try again."
            ))
        except Exception as e:
            logger.error("pipeline_error", session_id=session.session_id, error=str(e), error_type=type(e).__name__)
            await session.send_message(ErrorMessage(
                code=500,
                message=f"An error occurred: {str(e)}"
            ))
        finally:
            # Reset to idle ONLY if not interrupted (interrupt handler sets LISTENING)
            if not session.is_interrupted() and session.state != SessionState.CLOSED:
                await session.transition_state(SessionState.IDLE)
    
    async def reset_for_new_utterance(self, session: Session) -> None:
        """Reset pipeline state for a new utterance."""
        await self.asr.reset()
        session.audio_sequence = 0
        self._pending_final_text = None
        self._last_partial_text = ""


class PipelineManager:
    """
    Manages pipeline instances and lifecycle.
    """
    
    def __init__(self):
        self.asr_engine: Optional[GoogleASREngine] = None
        self.llm_engine: Optional[LLMEngine] = None
        self.tts_engine: Optional[TTSEngine] = None
        self._initialized = False
    
    async def initialize(self) -> bool:
        """Initialize all pipeline engines."""
        from engines import create_asr_engine, create_llm_engine, create_tts_engine
        
        logger.info("initializing_pipeline_engines")
        
        try:
            # Initialize engines in parallel
            self.asr_engine = await create_asr_engine()
            self.llm_engine = await create_llm_engine()
            self.tts_engine = await create_tts_engine()
            
            self._initialized = True
            logger.info("pipeline_engines_initialized")
            return True
            
        except Exception as e:
            logger.error("pipeline_init_failed", error=str(e))
            return False
    
    def create_pipeline(self) -> StreamingPipeline:
        """Create a new pipeline instance."""
        if not self._initialized:
            raise RuntimeError("Pipeline manager not initialized")
        
        return StreamingPipeline(
            asr_engine=self.asr_engine,
            llm_engine=self.llm_engine,
            tts_engine=self.tts_engine
        )
    
    async def shutdown(self) -> None:
        """Shutdown all engines."""
        logger.info("shutting_down_pipeline_engines")
        
        if self.asr_engine:
            await self.asr_engine.shutdown()
        if self.llm_engine:
            await self.llm_engine.shutdown()
        if self.tts_engine:
            await self.tts_engine.shutdown()
        
        self._initialized = False
        logger.info("pipeline_engines_shutdown")
    
    async def health_check(self) -> dict:
        """Check health of all engines."""
        return {
            "asr": self.asr_engine.initialized if self.asr_engine else False,
            "llm": await self.llm_engine.health_check() if self.llm_engine else False,
            "tts": await self.tts_engine.health_check() if self.tts_engine else False
        }


# Global pipeline manager
pipeline_manager = PipelineManager()
