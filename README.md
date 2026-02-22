<div align="center">

# 🤖 Zeni — Real-Time Bilingual Voice AI

**An ultra-low-latency voice assistant deployed as a physical AI robot receptionist**
**Built by B.Tech CSE (AI & ML) students — Graphic Era Hill University, Bhimtal (Batch 2023–2027)**

[![Python](https://img.shields.io/badge/Python-3.10+-3776AB?style=flat&logo=python&logoColor=white)](https://python.org)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.108-009688?style=flat&logo=fastapi&logoColor=white)](https://fastapi.tiangolo.com)
[![Android](https://img.shields.io/badge/Android-App-3DDC84?style=flat&logo=android&logoColor=white)](android/)
[![Groq](https://img.shields.io/badge/LLM-Groq-F55036?style=flat)](https://groq.com)
[![Google Cloud](https://img.shields.io/badge/ASR%20%26%20TTS-Google%20Cloud-4285F4?style=flat&logo=googlecloud&logoColor=white)](https://cloud.google.com)

> **~500ms first-word latency** · Full-duplex streaming · Hindi + English · Robot body control · Computer vision

</div>

---

## 📖 What Is Zeni?

Zeni is a **production-grade, real-time bilingual voice AI assistant** built for Graphic Era Hill University. Students and visitors interact with Zeni by speaking to an Android app — within **~500 milliseconds**, Zeni begins speaking back. It handles Hindi and English seamlessly, animates a Lottie lip-sync avatar, can physically move its robot body, and sees through a camera via computer vision.

The system evolved from a basic offline LLaMA/RAG chatbot into a fully optimized streaming AI pipeline through multiple major architectural iterations. Every millisecond of latency was fought for deliberately.

---

## ✨ Features

| Feature | Details |
|---------|---------|
| ⚡ **Ultra-low latency** | ~500ms first-word latency end-to-end |
| 🎙️ **Speculative ASR** | RAG + LLM pre-warm on high-confidence partial transcripts |
| 🌐 **Bilingual** | Hindi & English auto-detection per utterance |
| 🤖 **Robot control** | LLM decides autonomously when to move (forward/back/turn) |
| 👁️ **Computer vision** | Parallel pre-analysis while user speaks → near-zero vision latency |
| 🔊 **Zero-buffer TTS** | Audio output begins with the first LLM token |
| 📚 **RAG pipeline** | ChromaDB + multilingual-e5-small for accurate college FAQ retrieval |
| ⚡ **Full-duplex** | Talk and listen simultaneously; barge-in interruption supported |
| 🎭 **Personality modes** | Assistant / Human / General — switchable live via admin panel |
| 🛡️ **Admin dashboard** | Web panel for FAQ management, real-time index rebuild, session monitoring |

---

## 🏗️ Architecture

```
Android App (Java)
     │
     │  WebSocket — binary PCM frames (33% less overhead vs Base64)
     ▼
FastAPI Server (uvicorn + uvloop)
     │
     ├─► Google Cloud ASR ──► Speculative callback (pre-warms LLM on 80%+ confidence partial)
     │                         is_final=True ──► Direct callback (no polling)
     │
     ├─► Groq LLM ──────────► llama-3.3-70b-versatile, streaming, ~200ms first token
     │    ├── RAG context injected (ChromaDB + multilingual-e5-small)
     │    ├── API key rotation (rate-limit aware, round-robin)
     │    └── Function calling: look_with_eyes | control_robot
     │
     ├─► Vision Engine ─────► Llama 4 Maverick 17B (parallel pre-analysis)
     │                         Analyzes camera frame WHILE user is still speaking
     │
     └─► Google Cloud TTS ──► streaming_synthesize, zero buffering
              │
              └─► Android ── Lottie lip-sync animation + audio playback
```

---

## 🧰 Tech Stack

| Layer | Technology |
|-------|-----------|
| **Server Framework** | FastAPI + uvicorn + uvloop |
| **LLM** | Groq Cloud — `llama-3.3-70b-versatile` |
| **ASR** | Google Cloud Speech-to-Text (bidirectional streaming) |
| **TTS** | Google Cloud Text-to-Speech (streaming synthesize) |
| **Vision** | Groq — `meta-llama/llama-4-maverick-17b-128e-instruct` |
| **RAG / Embeddings** | ChromaDB + `intfloat/multilingual-e5-small` (MPS/CUDA/CPU) |
| **Transport** | WebSocket — binary + JSON frames, full-duplex |
| **Android App** | Java/Kotlin + Lottie animation |
| **Admin Panel** | FastAPI router, token-based auth, static HTML/JS |
| **Config** | YAML + dotenv |

---

## 📋 Prerequisites

**Server:**
- Python 3.10+
- A [Google Cloud](https://cloud.google.com) project with:
  - Cloud Speech-to-Text API enabled
  - Cloud Text-to-Speech API enabled
  - A Service Account with the above roles (download JSON key)
- A [Groq](https://console.groq.com) account (free tier available)
- A [Google AI Studio](https://aistudio.google.com) API key (Gemini, for Gemini TTS)

**Android:**
- Android Studio (Hedgehog or later)
- Android device / emulator (API 26+)

---

## 🚀 Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/YOUR_USERNAME/zeni.git
cd zeni
```

### 2. Server Setup

```bash
cd server

# Create and activate virtual environment
python3 -m venv venv
source venv/bin/activate       # macOS/Linux
# venv\Scripts\activate       # Windows

# Install dependencies
pip install -r requirements.txt
```

### 3. Environment Configuration

```bash
# Copy the example file
cp .env.example .env

# Edit .env and fill in your real values
nano .env    # or open in your editor
```

**Required values in `.env`:**

| Variable | Description | Where to Get |
|----------|-------------|--------------|
| `GROQ_API_KEY_1` | Primary Groq API key | [console.groq.com](https://console.groq.com) |
| `GROQ_API_KEY_2` | (Optional) Second key for rotation | Same |
| `GOOGLE_APPLICATION_CREDENTIALS` | Path to your GCP service account JSON | Google Cloud Console |
| `GEMINI_API_KEY` | Gemini API key | [aistudio.google.com](https://aistudio.google.com/app/apikey) |
| `GCP_PROJECT_ID` | Your GCP project ID | Cloud Console → Project Settings |
| `GCP_PROJECT_NUMBER` | Your GCP project number | Cloud Console → Project Settings |
| `ADMIN_PASSWORD` | Admin dashboard password | Choose a strong one |

### 4. Google Service Account Key

1. Go to [Google Cloud Console](https://console.cloud.google.com) → IAM & Admin → Service Accounts
2. Create a service account with roles: `Cloud Speech-to-Text User` + `Cloud Text-to-Speech User`
3. Download the JSON key
4. Save it as `ZENI-TTS-KEY.json` in the **project root** (next to `server/`)
5. The key file is already in `.gitignore` — it will never be committed

> See `ZENI-TTS-KEY.example.json` for the expected file structure.

### 5. Run the Server

```bash
cd server
source venv/bin/activate

# Make startup script executable (first time only)
chmod +x start_zeni.sh

# Start Zeni
./start_zeni.sh
```

Server starts at: `http://0.0.0.0:8765`
- WebSocket voice endpoint: `ws://YOUR_IP:8765/voice`
- Health check: `http://YOUR_IP:8765/health`
- Metrics: `http://YOUR_IP:8765/metrics`
- Admin panel: `http://YOUR_IP:8765/admin`

### 6. Android App Setup

1. Open `android/` in Android Studio
2. Let Gradle sync complete
3. Build and run on your device
4. In the app settings, set the server URL: `ws://YOUR_SERVER_IP:8765/voice`
5. Tap the mic button and talk!

---

## ⚙️ Configuration

All non-secret configuration lives in `server/config/config.yaml`. Key settings:

```yaml
llm:
  model: "llama-3.3-70b-versatile"   # Groq model to use
  max_tokens: 500                     # Max response length
  temperature: 0.3                    # Lower = more factual

tts:
  voice_name: "Kore"                  # Options: Puck, Charon, Kore, Fenrir, Aoede, Schedar

asr:
  language_detect: true               # Auto-detect Hindi/English
  model: "command_and_search"         # Optimized for short voice commands

performance:
  max_sessions: 20                    # Max concurrent users
  session_timeout: 300                # 5 minutes inactivity
```

---

## 📁 Project Structure

```
Zeni/
├── server/                  # Python backend
│   ├── server.py            # Main FastAPI application + WebSocket handler
│   ├── engines/             # Core AI engines
│   │   ├── google_asr.py    # ASR: bidirectional streaming + speculative execution
│   │   ├── llm.py           # LLM: Groq streaming + function calling + RAG injection
│   │   ├── tts.py           # TTS: zero-buffer Google Cloud streaming
│   │   ├── vision.py        # Vision: parallel pre-analysis pipeline
│   │   ├── rag.py           # RAG: ChromaDB + multilingual-e5-small embeddings
│   │   └── actions.py       # Action engine for robot control
│   ├── core/
│   │   ├── pipeline.py      # Streaming pipeline orchestration
│   │   ├── session.py       # Session lifecycle management
│   │   ├── protocol.py      # WebSocket message protocol definitions
│   │   ├── config.py        # Config loader (YAML + env)
│   │   └── logging.py       # Structured logging + latency tracking
│   ├── admin/               # Admin dashboard
│   │   ├── routes.py        # FAQ management, session view, metrics
│   │   ├── auth.py          # Token-based auth (reads from env)
│   │   └── static/          # Admin panel HTML/JS/CSS
│   ├── data/
│   │   └── faq.json         # College FAQ data (source of truth for RAG)
│   ├── config/
│   │   └── config.yaml      # Main configuration file
│   ├── requirements.txt
│   ├── .env.example         # Environment variable template
│   └── start_zeni.sh        # Startup script
│
├── android/                 # Android app (Java)
│   └── app/                 # Main application module
│
├── docs/
│   ├── PROTOCOL.md          # WebSocket message protocol documentation
│   └── CONFIG_UPDATES.md    # Configuration change history
│
├── Placement/               # University placement photos (served via API)
├── ZENI-TTS-KEY.example.json  # Service account key template (safe to commit)
├── .gitignore
└── README.md
```

---

## 🔒 Security Notes

> [!IMPORTANT]
> **Never commit real credentials.** The following are already in `.gitignore`:
> - `ZENI-TTS-KEY.json` — Google Service Account private key
> - `server/.env` — your real environment variables

**Best practices followed in this codebase:**
- All secrets loaded via environment variables at runtime
- Admin password hashed with SHA-256, never stored in plaintext in code
- YAML config uses `${ENV_VAR}` placeholders for API keys
- Service account key file pattern `*-KEY.json` is globally gitignored

**Before deploying to production:**
- [ ] Set `ENVIRONMENT=production` and `LOG_LEVEL=WARNING` in `.env`
- [ ] Use a strong, unique `ADMIN_PASSWORD`
- [ ] Restrict CORS origins in `server.py` (currently `allow_origins=["*"]`)
- [ ] Consider adding HTTPS / reverse proxy (nginx) in front of the server
- [ ] Rotate API keys periodically via Google Cloud Console and Groq

---

## 🧠 How the Latency Optimizations Work

The ~500ms end-to-end latency comes from deliberate engineering at every layer:

1. **Speculative ASR** → RAG search starts on 80%+ confidence *partials* before `is_final=True`
2. **Groq LLM** → ~200ms first token (10× faster than local Ollama), persistent connection pool
3. **Zero-buffer TTS** → Audio plays while LLM is still generating the rest of the sentence
4. **Binary WebSocket frames** → 33% less overhead versus Base64-encoded JSON audio
5. **Parallel vision** → Camera frame analyzed *while user is speaking* → cache hit = instant
6. **Direct callbacks** → No polling loops; `is_final` fires an immediate `asyncio.create_task`

---

## 📊 Performance Metrics

Access live metrics at `http://YOUR_IP:8765/metrics`:

```json
{
  "latency": {
    "asr": { "avg_ms": 180, "p95_ms": 350 },
    "llm": { "avg_ms": 210, "p95_ms": 400 },
    "tts": { "avg_ms": 95,  "p95_ms": 180 },
    "pipeline": { "avg_ms": 490, "p95_ms": 820 }
  }
}
```

---

## 🤝 Contributing

This project was built as a real-world AI system at a university. Pull requests, issues, and suggestions are welcome!

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/your-feature`
3. Commit your changes: `git commit -m "feat: add your feature"`
4. Push and open a Pull Request

---

## 📄 License

This project is licensed under the **MIT License** — see [LICENSE](LICENSE) for details.

---

<div align="center">

**Built with ❤️ by B.Tech CSE (AI & ML) students**
**Graphic Era Hill University, Bhimtal — Batch 2023–2027**

</div>
