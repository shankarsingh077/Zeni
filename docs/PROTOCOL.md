# Zeni Protocol Specification

This document defines the WebSocket communication protocol between the Zeni Android client and Mac server.

## Connection

- **Endpoint**: `ws://{server_ip}:8765/voice`
- **Protocol**: WebSocket (RFC 6455)
- **Format**: JSON messages

## Message Types

### Client → Server

#### 1. Session Start
Initiates a new voice session.

```json
{
  "type": "session_start",
  "session_id": "uuid",
  "config": {
    "sample_rate": 16000,
    "language_preference": "auto",
    "push_to_talk": false
  },
  "timestamp": 1234567890
}
```

#### 2. Audio Frame
Streams audio data to server.

```json
{
  "type": "audio_frame",
  "timestamp": 1234567890,
  "data": "<base64_pcm_data>",
  "sequence": 42
}
```

- **Audio Format**: 16kHz, 16-bit, mono PCM
- **Frame Size**: 20ms (320 samples, 640 bytes)
- **Encoding**: Base64

#### 3. Interrupt
Signals user wants to interrupt AI response.

```json
{
  "type": "interrupt",
  "timestamp": 1234567890
}
```

#### 4. Heartbeat
Keep-alive signal (every 10 seconds).

```json
{
  "type": "heartbeat",
  "timestamp": 1234567890
}
```

#### 5. Session End
Terminates the session.

```json
{
  "type": "session_end",
  "session_id": "uuid",
  "timestamp": 1234567890
}
```

### Server → Client

#### 1. Session Acknowledgment
Confirms session creation.

```json
{
  "type": "session_ack",
  "session_id": "uuid",
  "status": "connected",
  "timestamp": 1234567890
}
```

#### 2. State Change
Notifies client of state transition.

```json
{
  "type": "state_change",
  "state": "listening",
  "previous_state": "idle",
  "timestamp": 1234567890
}
```

**States**: `idle`, `listening`, `transcribing`, `generating`, `speaking`, `interrupted`, `error`, `closed`

#### 3. Transcript (Partial)
Real-time transcription update.

```json
{
  "type": "transcript_partial",
  "text": "Hello how are",
  "language": "en",
  "timestamp": 1234567890
}
```

#### 4. Transcript (Final)
Complete transcription of utterance.

```json
{
  "type": "transcript_final",
  "text": "Hello how are you",
  "confidence": 0.95,
  "language": "en",
  "timestamp": 1234567890
}
```

#### 5. LLM Token
Streaming LLM response token.

```json
{
  "type": "llm_token",
  "token": "I'm",
  "sequence": 1
}
```

#### 6. LLM Complete
Complete LLM response.

```json
{
  "type": "llm_complete",
  "full_text": "I'm doing well, thank you!",
  "timestamp": 1234567890
}
```

#### 7. Audio Response
TTS audio chunk.

```json
{
  "type": "audio_response",
  "sequence": 10,
  "data": "<base64_pcm_data>",
  "final": false,
  "sample_rate": 24000
}
```

- **Audio Format**: 24kHz (or specified), 16-bit, mono PCM
- **Encoding**: Base64

#### 8. Playback Stop
Instructs client to stop audio playback.

```json
{
  "type": "playback_stop",
  "timestamp": 1234567890
}
```

#### 9. Error
Error notification.

```json
{
  "type": "error",
  "code": 500,
  "message": "ASR failed",
  "details": {},
  "timestamp": 1234567890
}
```

#### 10. Heartbeat Acknowledgment
Response to heartbeat.

```json
{
  "type": "heartbeat_ack",
  "timestamp": 1234567890
}
```

## Session Flow

```
Client                                 Server
   |                                      |
   |-------- session_start ------------->|
   |<------- session_ack ----------------|
   |<------- state_change (idle) --------|
   |                                      |
   |-------- audio_frame (1) ----------->|
   |<------- state_change (listening) ---|
   |-------- audio_frame (2) ----------->|
   |<------- transcript_partial ---------|
   |-------- audio_frame (n) ----------->|
   |                                      |
   |         [silence detected]           |
   |<------- state_change (transcribing)-|
   |<------- transcript_final -----------|
   |<------- state_change (generating) --|
   |<------- llm_token (1) --------------|
   |<------- llm_token (2) --------------|
   |<------- state_change (speaking) ----|
   |<------- audio_response (1) ---------|
   |<------- audio_response (2) ---------|
   |<------- llm_complete ---------------|
   |<------- audio_response (final) -----|
   |<------- state_change (idle) --------|
   |                                      |
```

## Interruption Flow

```
Client                                 Server
   |                                      |
   |         [AI is speaking]             |
   |<------- audio_response -------------|
   |-------- interrupt ----------------->|
   |<------- playback_stop --------------|
   |<------- state_change (listening) ---|
   |-------- audio_frame --------------->|
   |         [new conversation starts]    |
   |                                      |
```

## Error Codes

| Code | Description |
|------|-------------|
| 400 | Bad Request - Invalid message format |
| 401 | Unauthorized - Invalid session |
| 429 | Too Many Requests - Rate limited |
| 500 | Internal Server Error |
| 503 | Service Unavailable - Engine failure |

## Connection Management

- **Heartbeat Interval**: 10 seconds
- **Session Timeout**: 5 minutes of inactivity
- **Reconnection**: Exponential backoff (1s, 2s, 4s, ... up to 30s)
