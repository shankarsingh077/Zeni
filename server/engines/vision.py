"""
Zeni Vision Engine - Proactive Analysis

OPTIMIZED FLOW (Parallel Processing):
1. User presses mic → Android sends image immediately
2. Server receives image → STARTS PRE-ANALYSIS IN PARALLEL with ASR
3. Pre-analysis runs while user is still speaking
4. When LLM needs vision → uses cached pre-analysis result (INSTANT!)

This eliminates 1-3 seconds of vision latency by running in parallel with speech.
"""

import asyncio
import time
from typing import Optional, Dict, Callable, Awaitable
import aiohttp

from core.config import config
from core.logging import get_logger

logger = get_logger("vision")

# Maverick vision model on Groq
VISION_MODEL = "meta-llama/llama-4-maverick-17b-128e-instruct"
GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions"

# Type for image request callback: async function that sends request and returns image
ImageRequestCallback = Callable[[str], Awaitable[Optional[str]]]  # session_id -> image_base64


class VisionEngine:
    """
    Proactive vision engine - analyzes image in parallel while user speaks.
    """
    
    def __init__(self):
        self.enabled = config.vision.enabled if hasattr(config, 'vision') and config.vision else True
        self.api_keys = config.llm.api_keys if config.llm.api_keys else []
        self._current_key_index = 0
        
        # Single image storage: (session_id, image_base64, timestamp)
        self._current_image: Optional[tuple] = None
        
        # Pre-analysis cache: (session_id, analysis_result, timestamp)
        # This runs in PARALLEL with ASR - ready when LLM needs it!
        self._pre_analysis_cache: Optional[tuple] = None
        self._pre_analysis_task: Optional[asyncio.Task] = None
        
        self._initialized = False
    
    async def initialize(self) -> bool:
        """Initialize the vision engine."""
        if not self.api_keys:
            logger.warning("vision_no_api_keys")
            return False
        
        self._initialized = True
        logger.info("vision_initialized", model=VISION_MODEL)
        return True
    
    def _get_api_key(self) -> str:
        """Get next API key (round-robin)."""
        if not self.api_keys:
            return ""
        key = self.api_keys[self._current_key_index]
        self._current_key_index = (self._current_key_index + 1) % len(self.api_keys)
        return key
    
    def receive_image_and_preanalyze(self, session_id: str, image_base64: str) -> None:
        """
        Receive image and START PRE-ANALYSIS IMMEDIATELY.
        This runs in PARALLEL while user is still speaking.
        """
        self._current_image = (session_id, image_base64, time.time())
        logger.info("image_received_starting_preanalysis", session_id=session_id[:8], size=len(image_base64))
        
        # Cancel any existing pre-analysis
        if self._pre_analysis_task and not self._pre_analysis_task.done():
            self._pre_analysis_task.cancel()
        
        # Start pre-analysis in background (PARALLEL with ASR!)
        self._pre_analysis_task = asyncio.create_task(
            self._run_pre_analysis(session_id, image_base64)
        )
    
    async def _run_pre_analysis(self, session_id: str, image_base64: str) -> None:
        """
        Pre-analyze what we see while user speaks.
        Result is cached for instant retrieval when LLM needs it.
        """
        start_time = time.time()
        api_key = self._get_api_key()
        
        if not api_key:
            return
        
        # Natural observation prompt with gender detection for Hindi grammar
        prompt = """You are looking at someone/something right now. Describe what you observe:

IMPORTANT: Write naturally as if SEEING this live.
- Say "I see", "there's", "you have" - NOT "the image shows" or "the photo depicts"

For a PERSON (most important):
FIRST LINE: State their gender clearly: "I see a [male/female/person]..."

Then describe SPECIFIC details:
- Face: expression, smile, features
- Hair: color, style, length (curly, straight, short, long, beard, etc.)
- Clothing: what they're wearing with COLORS and STYLE
- Accessories: glasses, earrings, watch, cap, etc.
- Overall vibe: friendly, professional, casual, stylish, etc.

BE SPECIFIC - not "nice outfit" but "wearing a blue striped shirt" 
Not "nice hair" but "curly black hair" or "short brown hair with highlights"

For DOCUMENTS/TEXT:
- Read ALL visible text word by word
- Document type
- Key information

For OBJECTS:
- What it is specifically
- Brand, colors, condition

Focus on SPECIFIC observable details that could be mentioned in conversation."""

        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json"
        }
        
        payload = {
            "model": VISION_MODEL,
            "messages": [{
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"}
                    }
                ]
            }],
            "max_tokens": 1500,  # Let model generate full detailed analysis
            "temperature": 0.2
        }
        
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    GROQ_API_URL,
                    json=payload,
                    headers=headers,
                    timeout=aiohttp.ClientTimeout(total=15)  # Longer timeout for detailed analysis
                ) as response:
                    if response.status == 200:
                        result = await response.json()
                        choices = result.get("choices", [])
                        if choices:
                            content = choices[0].get("message", {}).get("content", "")
                            latency_ms = (time.time() - start_time) * 1000
                            
                            # Cache the result
                            self._pre_analysis_cache = (session_id, content, time.time())
                            logger.info("pre_analysis_complete", 
                                       session_id=session_id[:8],
                                       latency_ms=f"{latency_ms:.0f}",
                                       result_length=len(content),
                                       result_preview=content[:200])
                    else:
                        logger.warning("pre_analysis_api_error", status=response.status)
        except asyncio.CancelledError:
            logger.info("pre_analysis_cancelled", session_id=session_id[:8])
        except Exception as e:
            logger.warning("pre_analysis_error", error=str(e))
    
    def get_pre_analysis(self, session_id: str, max_age_seconds: float = 30.0) -> Optional[str]:
        """
        Get cached pre-analysis result (INSTANT - no API call!).
        Returns None if no result or too old.
        """
        if not self._pre_analysis_cache:
            return None
        
        stored_session, analysis, timestamp = self._pre_analysis_cache
        
        if stored_session != session_id:
            return None
        
        age = time.time() - timestamp
        if age > max_age_seconds:
            return None
        
        logger.info("pre_analysis_used", session_id=session_id[:8], age_ms=int(age*1000))
        return analysis
    
    async def wait_for_pre_analysis(self, session_id: str, timeout: float = 5.0) -> Optional[str]:
        """
        Wait for pre-analysis to complete if it's still running.
        Returns the result if available within timeout.
        """
        start = time.time()
        while time.time() - start < timeout:
            # Check if we already have result
            result = self.get_pre_analysis(session_id)
            if result:
                return result
            
            # Check if task is still running
            if self._pre_analysis_task and not self._pre_analysis_task.done():
                await asyncio.sleep(0.2)  # Wait a bit
                continue
            
            # Task done but no result? Check one more time
            result = self.get_pre_analysis(session_id)
            if result:
                return result
            
            # No task running and no result
            break
        
        return None
    
    def has_image(self, session_id: str) -> bool:
        """Check if we have an image for this session."""
        if not self._current_image:
            return False
        stored_session, _, _ = self._current_image
        return stored_session == session_id
    
    def get_present_image(self, session_id: str, max_age_seconds: float = 30.0) -> Optional[str]:
        """Get the current image for this session."""
        if not self._current_image:
            return None
        
        stored_session, image_base64, timestamp = self._current_image
        
        if stored_session != session_id:
            return None
        
        age = time.time() - timestamp
        if age > max_age_seconds:
            return None
        
        return image_base64
    
    async def analyze(self, session_id: str, user_query: str, image_base64: Optional[str] = None) -> str:
        """
        Analyze image - uses pre-analysis if available, otherwise does fresh analysis.
        
        OPTIMIZED: If pre-analysis is available and matches query, uses it (INSTANT!).
        Otherwise falls back to targeted analysis.
        """
        if not self._initialized:
            return "Vision system not ready."
        
        # First check if we have relevant pre-analysis
        pre_analysis = self.get_pre_analysis(session_id)
        if pre_analysis:
            # We have pre-analysis! Use it to answer the user's question.
            # The LLM will use this context to formulate the response.
            logger.info("using_pre_analysis", session_id=session_id[:8])
            return pre_analysis
        
        # No pre-analysis available - do targeted analysis (slower)
        if not image_base64:
            image_base64 = self.get_present_image(session_id)
        
        if not image_base64:
            return "No camera image available."
        
        return await self._targeted_analysis(session_id, user_query, image_base64)
    
    async def _targeted_analysis(self, session_id: str, user_query: str, image_base64: str) -> str:
        """Targeted analysis for specific user query (fallback)."""
        start_time = time.time()
        api_key = self._get_api_key()
        
        if not api_key:
            return "No API key available."
        
        prompt = f"""The user asked: "{user_query}"

Analyze this image and answer their question directly:

1. If they're asking how they LOOK:
   - Describe their appearance in detail (face, expression, clothing, style)
   - Be positive and specific ("You're wearing a blue shirt", not "I see a person")
   - Comment on their look honestly and helpfully

2. If showing a DOCUMENT:
   - Read ALL visible text completely
   - Transcribe numbers, names, dates you see
   - Identify the document type

3. If showing an OBJECT:
   - Identify what it is exactly
   - Read any labels/text on it
   - Describe relevant details

Answer their specific question with real details from the image.
Never say "I cannot see" or "the image is blurry" - describe what IS visible."""

        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json"
        }
        
        payload = {
            "model": VISION_MODEL,
            "messages": [{
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"}
                    }
                ]
            }],
            "max_tokens": 1500,  # Let model generate full detailed response
            "temperature": 0.3
        }
        
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    GROQ_API_URL,
                    json=payload,
                    headers=headers,
                    timeout=aiohttp.ClientTimeout(total=15)
                ) as response:
                    if response.status == 200:
                        result = await response.json()
                        choices = result.get("choices", [])
                        if choices:
                            content = choices[0].get("message", {}).get("content", "")
                            latency_ms = (time.time() - start_time) * 1000
                            logger.info("targeted_analysis_complete", 
                                       session_id=session_id[:8],
                                       latency_ms=f"{latency_ms:.0f}")
                            return content
                    else:
                        error = await response.text()
                        logger.warning("vision_api_error", status=response.status, error=error[:100])
                        return "Vision analysis failed."
        except Exception as e:
            logger.warning("vision_exception", error=str(e))
            return f"Vision error: {str(e)}"
        
        return "Could not analyze the image."
    
    async def request_and_wait(self, session_id: str, send_request_fn: Callable[[], Awaitable[None]], timeout: float = 5.0) -> Optional[str]:
        """Legacy fallback - request image from client."""
        logger.info("requesting_image_fallback", session_id=session_id[:8])
        await send_request_fn()
        
        start = time.time()
        while time.time() - start < timeout:
            if self.has_image(session_id):
                return self.get_present_image(session_id)
            await asyncio.sleep(0.1)
        return None


# Global singleton
_vision_engine: Optional[VisionEngine] = None


def get_vision_engine() -> VisionEngine:
    """Get the global vision engine instance."""
    global _vision_engine
    if _vision_engine is None:
        _vision_engine = VisionEngine()
    return _vision_engine


async def initialize_vision() -> bool:
    """Initialize the global vision engine."""
    engine = get_vision_engine()
    return await engine.initialize()
