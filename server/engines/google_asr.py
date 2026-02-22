
import asyncio
import queue
from typing import Optional, AsyncGenerator, Callable
import time

from google.cloud import speech
from google.oauth2 import service_account

from core.config import config
from core.protocol import Language
from core.logging import get_logger
from engines.asr import ASRResult

logger = get_logger("google_asr")


# Confidence threshold for speculative execution
SPECULATIVE_CONFIDENCE_THRESHOLD = 0.80
# Stability threshold - if partial is stable for this many chars, consider it reliable
STABILITY_THRESHOLD = 3


class GoogleASREngine:
    """
    ULTRA-OPTIMIZED ASR Engine using Google Cloud Speech-to-Text.
    
    Key Optimizations:
    1. Speculative execution on high-confidence partials
    2. Reduced timeouts for faster finalization
    3. Direct callback for immediate processing
    4. Stability detection for reliable partials
    """
    
    def __init__(self):
        self.client: Optional[speech.SpeechAsyncClient] = None
        self.audio_queue: asyncio.Queue = asyncio.Queue()
        self.result_queue: asyncio.Queue = asyncio.Queue()
        self.stream_task: Optional[asyncio.Task] = None
        self.initialized = False
        self.current_language = Language.ENGLISH
        
        # Direct callback for final results - NO POLLING!
        self._final_callback: Optional[Callable] = None
        
        # NEW: Speculative execution callback for high-confidence partials
        self._speculative_callback: Optional[Callable] = None
        
        # Stream management
        self._stream_start_time = 0.0
        self._new_stream_needed = True
        self._last_final_text: Optional[str] = None
        
        # Stability tracking for speculative execution
        self._last_partial_text = ""
        self._partial_stable_count = 0
        self._speculative_triggered = False
    
    async def initialize(self) -> bool:
        """Initialize Google Cloud Speech client."""
        try:
            logger.info("initializing_google_asr", project=config.google_cloud.project_id)
            
            creds = service_account.Credentials.from_service_account_file(
                config.google_cloud.credentials_path
            )
            
            self.client = speech.SpeechAsyncClient(credentials=creds)
            self.initialized = True
            
            # Start streaming loop in background
            self.stream_task = asyncio.create_task(self._streaming_loop())
            
            logger.info("google_asr_initialized")
            return True
            
        except Exception as e:
            logger.error("google_asr_init_failed", error=str(e))
            return False

    async def _request_generator(self) -> AsyncGenerator[speech.StreamingRecognizeRequest, None]:
        """Yields streaming requests from audio queue."""
        
        # OPTIMIZED: Use command_and_search for faster voice command recognition
        recognition_config = speech.RecognitionConfig(
            encoding=speech.RecognitionConfig.AudioEncoding.LINEAR16,
            sample_rate_hertz=config.audio.sample_rate,
            language_code=config.asr.language_code_en,
            alternative_language_codes=[config.asr.language_code_hi] if config.asr.language_detect else [],
            enable_automatic_punctuation=True,
            model="command_and_search",  # FASTER: Optimized for short voice commands
            use_enhanced=True,  # Better accuracy
        )
        
        streaming_config = speech.StreamingRecognitionConfig(
            config=recognition_config,
            interim_results=True,
            single_utterance=False
        )
        
        yield speech.StreamingRecognizeRequest(streaming_config=streaming_config)
        
        # Audio chunks
        while True:
            try:
                # OPTIMIZED: 2s timeout (was 3s) for faster response
                chunk = await asyncio.wait_for(self.audio_queue.get(), timeout=2.0)
                
                if chunk is None:
                    logger.debug("stream_received_stop_signal")
                    break
                logger.debug("sending_audio_to_google", chunk_size=len(chunk))
                yield speech.StreamingRecognizeRequest(audio_content=chunk)
            except asyncio.TimeoutError:
                logger.debug("audio_stream_idle_timeout")
                break

    async def _streaming_loop(self):
        """
        Main bidirectional streaming loop.
        
        OPTIMIZED with speculative execution:
        - Triggers LLM on high-confidence partials
        - Faster final result processing
        """
        while self.initialized:
            try:
                # Wait for audio to be available before starting a stream
                if self.audio_queue.empty():
                    await asyncio.sleep(0.005)  # 5ms polling
                    continue

                logger.info("starting_google_stream")
                self._stream_start_time = time.time()
                self._speculative_triggered = False  # Reset for new stream
                
                # Create request generator
                requests = self._request_generator()
                
                # Call API
                responses = await self.client.streaming_recognize(requests=requests)
                
                async for response in responses:
                    if not response.results:
                        continue
                    
                    # Process ALL results
                    for result in response.results:
                        if not result.alternatives:
                            continue
                            
                        alt = result.alternatives[0]
                        transcript = alt.transcript
                        is_final = result.is_final
                        confidence = alt.confidence if alt.confidence else 0.0
                        
                        if not transcript.strip():
                            continue
                        
                        # Detect language
                        detected_lang = self.current_language
                        if hasattr(result, "language_code") and result.language_code:
                            if "hi" in result.language_code.lower():
                                detected_lang = Language.HINDI
                            else:
                                detected_lang = Language.ENGLISH

                        asr_result = ASRResult(
                            text=transcript,
                            is_partial=not is_final,
                            confidence=confidence,
                            language=detected_lang,
                            is_final=is_final
                        )
                        
                        # Put in queue for partials
                        await self.result_queue.put(asr_result)
                        
                        # SPECULATIVE EXECUTION: Trigger on high-confidence partial
                        if not is_final and not self._speculative_triggered:
                            # Check stability (same text appearing multiple times)
                            if transcript == self._last_partial_text:
                                self._partial_stable_count += 1
                            else:
                                self._partial_stable_count = 0
                                self._last_partial_text = transcript
                            
                            # Trigger speculative execution if:
                            # 1. High confidence OR
                            # 2. Stable partial (same text 3+ times) with decent length
                            should_speculate = (
                                (confidence >= SPECULATIVE_CONFIDENCE_THRESHOLD and len(transcript) >= 5) or
                                (self._partial_stable_count >= STABILITY_THRESHOLD and len(transcript) >= 10)
                            )
                            
                            if should_speculate and self._speculative_callback:
                                logger.info("speculative_execution_triggered",
                                           text=transcript[:50],
                                           confidence=confidence,
                                           stability=self._partial_stable_count)
                                self._speculative_triggered = True
                                try:
                                    asyncio.create_task(self._speculative_callback(asr_result))
                                except Exception as e:
                                    logger.error("speculative_callback_error", error=str(e))
                        
                        # FINAL RESULT: Immediate callback
                        if is_final:
                            logger.info("final_transcript", 
                                       text=transcript[:50], 
                                       confidence=confidence)
                            
                            if self._final_callback:
                                try:
                                    self._last_final_text = transcript
                                    asyncio.create_task(self._final_callback(asr_result))
                                except Exception as e:
                                    logger.error("final_callback_error", error=str(e))
                            else:
                                self._last_final_text = transcript
                            
                            # Reset speculative state
                            self._speculative_triggered = False
                            self._partial_stable_count = 0
                    
                    # Refresh stream before Google's limit
                    if time.time() - self._stream_start_time > 280:
                        logger.info("refreshing_google_stream_limit")
                        break

            except asyncio.CancelledError:
                logger.info("google_stream_cancelled")
                break
            except Exception as e:
                error_str = str(e)
                if "deadline" in error_str.lower() or "timeout" in error_str.lower():
                    logger.debug("google_stream_timeout", error=error_str)
                else:
                    logger.error("google_stream_error", error=error_str)
                await asyncio.sleep(0.05)  # Reduced backoff (was 0.1)

    async def process_audio(
        self, 
        audio_data: bytes,
        preferred_language: Language = Language.ENGLISH
    ) -> Optional[ASRResult]:
        """
        Process audio chunk. Puts into queue for background stream.
        Returns ONE result from result queue if available (for real-time streaming).
        """
        if not self.initialized:
            return None
            
        self.current_language = preferred_language
        
        # Add to send queue
        self.audio_queue.put_nowait(audio_data)
        
        # Get ONE result at a time for real-time streaming
        # Don't consume all results - let each audio frame process one result
        try:
            result = self.result_queue.get_nowait()
            return result
        except asyncio.QueueEmpty:
            return None

    async def finalize(self, preferred_language: Language = Language.ENGLISH) -> Optional[ASRResult]:
        """
        Force finalize - OPTIMIZED with faster timeout.
        """
        logger.info("google_asr_force_finalize", queue_size=self.audio_queue.qsize())
        
        # Signal end of stream
        self.audio_queue.put_nowait(None)
        
        # OPTIMIZED: 0.5s timeout (was 1s)
        best_result = None
        timeout = 0.5
        start = time.time()
        
        while time.time() - start < timeout:
            try:
                result = await asyncio.wait_for(self.result_queue.get(), timeout=0.03)  # 30ms (was 50ms)
                
                if result and result.text.strip():
                    logger.info("finalize_got_result", text=result.text[:50], is_final=result.is_final)
                    best_result = result
                    if result.is_final:
                        if result.text == self._last_final_text:
                            logger.info("finalize_ignoring_duplicate")
                            return None
                        self._last_final_text = result.text
                        return result
            except asyncio.TimeoutError:
                continue
                
        # Promote partial if no final
        if best_result:
            logger.info("finalize_promoting_partial", text=best_result.text[:50])
            best_result.is_final = True
            return best_result
            
        logger.info("finalize_no_result")
        return None

    def set_final_callback(self, callback: Callable):
        """Set callback for IMMEDIATE final result processing."""
        self._final_callback = callback
        logger.info("final_callback_registered")
    
    def set_speculative_callback(self, callback: Callable):
        """
        Set callback for SPECULATIVE execution on high-confidence partials.
        
        This allows starting LLM processing BEFORE is_final=True,
        potentially saving 200-500ms of latency.
        
        Args:
            callback: async function that takes ASRResult as parameter
        """
        self._speculative_callback = callback
        logger.info("speculative_callback_registered")
    
    async def reset(self):
        """Reset the ASR engine for a new utterance."""
        logger.info("resetting_google_asr")
        # Clear the result queue
        while not self.result_queue.empty():
            try:
                self.result_queue.get_nowait()
            except asyncio.QueueEmpty:
                break
        
        # Reset speculative execution state
        self._last_partial_text = ""
        self._partial_stable_count = 0
        self._speculative_triggered = False
        self._last_final_text = None
        
        logger.info("google_asr_reset_complete")
    
    async def shutdown(self) -> None:
        """Shutdown the ASR engine."""
        logger.info("shutting_down_google_asr")
        self.initialized = False
        
        # Cancel the streaming task
        if self.stream_task and not self.stream_task.done():
            self.stream_task.cancel()
            try:
                await self.stream_task
            except asyncio.CancelledError:
                pass
        
        # Signal the audio queue to stop
        await self.audio_queue.put(None)
        
        logger.info("google_asr_shutdown_complete")

