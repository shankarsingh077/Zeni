# Configuration Updates Summary

## Updated Models

### ASR (Vosk)
- **English**: `vosk-model-en-in-0.5` (1GB) - Indian English accent
- **Hindi**: `vosk-model-hi-0.22` (1.5GB)

### LLM (Ollama)
- **Primary**: `gpt-oss:120b-cloud` with API key rotation
- **Fallback**: `llama3.2` (local)
- **API Keys**: 4 keys configured with round-robin rotation
- **Rate Limit Handling**: Automatic fallback to local model on 429 error

### TTS (Gemini)
- **Model**: `gemini-2.5-flash-tts`
- **Voice**: Schedar
- **Locales**: 
  - English: `en-IN` (India)
  - Hindi: `hi-IN` (India)
- **Project**: zeni-484217 (projects/885915479156)
- **Format**: PCM, 24kHz

## API Key Rotation

The system now supports multiple API keys for the LLM service:

1. Keys are stored in `config.yaml` under `llm.api_keys` array
2. Round-robin rotation automatically cycles through keys
3. Add more keys by appending to the array
4. Enable/disable rotation with `api_key_rotation: true/false`

Example:
```yaml
llm:
  api_keys:
    - "key1.token1"
    - "key2.token2"
    - "key3.token3"
    # Add more keys here
  api_key_rotation: true
```

## Next Steps

1. Run `./setup.sh` to download updated Vosk models
2. Verify API keys in `config.yaml`
3. Start server with `./start_zeni.sh`
4. The system will automatically rotate keys and fallback to local model if needed
