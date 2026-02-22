"""
Zeni ASR Engine (Google Cloud)
Streaming speech recognition with bilingual support.
Removed Vosk for simplified, faster pipeline.
"""

from dataclasses import dataclass

from core.config import config
from core.protocol import Language
from core.logging import get_logger

logger = get_logger("asr")


@dataclass
class ASRResult:
    """ASR transcription result."""
    text: str
    is_partial: bool
    confidence: float
    language: Language
    is_final: bool = False  # End of utterance


async def create_asr_engine():
    """Factory function to create and initialize Google ASR engine."""
    logger.info("creating_google_asr_engine")
    from engines.google_asr import GoogleASREngine
    engine = GoogleASREngine()
    init_result = await engine.initialize()
    
    if not init_result:
        logger.error("google_asr_init_failed")
        raise RuntimeError("Failed to initialize Google ASR - no fallback available")
    
    return engine
