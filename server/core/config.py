"""
Zeni Configuration Module
Loads and validates configuration from YAML and environment variables.
"""

import os
from pathlib import Path
from typing import Optional
from functools import lru_cache

import yaml
from pydantic import BaseModel, Field, ConfigDict
from pydantic_settings import BaseSettings
from dotenv import load_dotenv

# Load environment variables
load_dotenv()


class ServerConfig(BaseModel):
    """Server configuration."""
    host: str = "0.0.0.0"
    port: int = 8765
    workers: int = 4
    log_level: str = "INFO"


class AudioConfig(BaseModel):
    """Audio configuration."""
    sample_rate: int = 16000
    frame_size: int = 320
    channels: int = 1
    bit_depth: int = 16


class GoogleCloudConfig(BaseModel):
    """Google Cloud configuration."""
    # Try to find the key file in project root or use env var
    credentials_path: str = Field(default_factory=lambda: os.getenv("GOOGLE_APPLICATION_CREDENTIALS", str(Path.cwd().parent / "ZENI-TTS-KEY.json")))
    project_id: str = "zeni-484217"


class ASRConfig(BaseModel):
    """ASR (Automatic Speech Recognition) configuration - Google Cloud STT."""
    model_config = ConfigDict(protected_namespaces=())
    
    provider: str = "google"  # Google Cloud Speech-to-Text only
    
    # Google STT
    language_code_en: str = "en-IN"
    language_code_hi: str = "hi-IN"
    use_enhanced: bool = True
    model: str = "command_and_search"  # Optimized for voice commands
    
    language_detect: bool = True
    partial_results: bool = True


class LLMConfig(BaseModel):
    """LLM configuration - Groq for ultra-fast inference."""
    provider: str = "groq"
    model: str = "llama-3.3-70b-versatile"  # Best Groq model
    fallback_model: Optional[str] = None  # No fallback - removed
    base_url: str = "https://api.groq.com/openai/v1"
    api_keys: list[str] = []
    api_key_rotation: bool = True
    temperature: float = 0.7
    max_tokens: int = 150  # Short for voice
    streaming: bool = True
    timeout: int = 15  # Groq is fast


class TTSConfig(BaseModel):
    """TTS (Text-to-Speech) configuration."""
    provider: str = "gemini"
    model: str = "gemini-2.5-flash-tts"
    project_name: str = "projects/885915479156"
    project_number: str = "885915479156"
    project_id: str = "zeni-484217"
    voice_name: str = "Schedar"
    voice_en: str = "en-IN"
    voice_hi: str = "hi-IN"
    speaking_rate: float = 1.1
    pitch: float = 0.0
    output_format: str = "PCM"
    output_sample_rate: int = 24000


class MemoryConfig(BaseModel):
    """Conversation memory configuration."""
    max_turns: int = 10
    max_tokens: int = 8192
    summarize_threshold: int = 6000


class PerformanceConfig(BaseModel):
    """Performance tuning configuration."""
    max_sessions: int = 20
    session_timeout: int = 300
    interrupt_threshold_ms: int = 50
    vad_speech_min_duration_ms: int = 200


class VisionConfig(BaseModel):
    """Vision configuration for visual context awareness."""
    enabled: bool = False
    provider: str = "gemini"  # gemini or groq
    model: str = "gemini-2.0-flash"
    api_key: str = ""  # Gemini API key for vision


class ZeniConfig(BaseModel):
    """Main Zeni configuration."""
    server: ServerConfig = Field(default_factory=ServerConfig)
    audio: AudioConfig = Field(default_factory=AudioConfig)
    google_cloud: GoogleCloudConfig = Field(default_factory=GoogleCloudConfig)
    asr: ASRConfig = Field(default_factory=ASRConfig)
    llm: LLMConfig = Field(default_factory=LLMConfig)
    tts: TTSConfig = Field(default_factory=TTSConfig)
    memory: MemoryConfig = Field(default_factory=MemoryConfig)
    performance: PerformanceConfig = Field(default_factory=PerformanceConfig)
    vision: VisionConfig = Field(default_factory=VisionConfig)
    system_prompt: str = """You are Zeni, a helpful voice AI assistant."""
    human_personality_prompt: str = """You are Zeni, a friendly AI. Talk naturally like a friend."""
    assistant_personality_prompt: str = """You are Zeni, a professional AI assistant. Be concise and formal."""
    general_system_prompt: str = """You are Zeni, a friendly AI. You can talk about anything!"""


class EnvironmentSettings(BaseSettings):
    """Environment-based settings."""
    gemini_api_key: Optional[str] = Field(default=None, alias="GEMINI_API_KEY")
    log_level: str = Field(default="WARNING", alias="LOG_LEVEL")  # Production default
    environment: str = Field(default="production", alias="ENVIRONMENT")
    zeni_host: str = Field(default="0.0.0.0", alias="ZENI_HOST")
    zeni_port: int = Field(default=8765, alias="ZENI_PORT")

    class Config:
        env_file = ".env"
        extra = "ignore"


def load_yaml_config(config_path: Optional[str] = None) -> dict:
    """Load configuration from YAML file."""
    if config_path is None:
        # Default config path
        config_path = Path(__file__).parent.parent / "config" / "config.yaml"
    else:
        config_path = Path(config_path)
    
    if not config_path.exists():
        return {}
    
    with open(config_path, "r") as f:
        return yaml.safe_load(f) or {}


@lru_cache()
def get_config() -> ZeniConfig:
    """Get the Zeni configuration (cached)."""
    yaml_config = load_yaml_config()
    return ZeniConfig(**yaml_config)


@lru_cache()
def get_env_settings() -> EnvironmentSettings:
    """Get environment settings (cached)."""
    return EnvironmentSettings()


# Global config instances
config = get_config()
env_settings = get_env_settings()
