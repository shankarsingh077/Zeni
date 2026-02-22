#!/bin/bash

# Zeni Setup Script
# Installs all dependencies for Zeni Voice AI Server

set -e

echo "🎤 Zeni Voice AI Setup"
echo "======================"

# Check OS
if [[ "$OSTYPE" != "darwin"* ]]; then
    echo "⚠️  This script is designed for macOS. Some commands may need adjustment."
fi

# Check for Homebrew
if ! command -v brew &> /dev/null; then
    echo "📦 Installing Homebrew..."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
fi

# Install system dependencies
echo "📦 Installing system dependencies..."
brew install python@3.11 portaudio ffmpeg wget

# Create models directory
echo "📁 Creating models directory..."
mkdir -p models/vosk

# Download Vosk models
echo "📥 Downloading Vosk models..."

cd models/vosk

# English (India) model
if [ ! -d "en-in" ]; then
    echo "  Downloading English (India) model (1GB)..."
    wget -q --show-progress https://alphacephei.com/vosk/models/vosk-model-en-in-0.5.zip
    unzip -q vosk-model-en-in-0.5.zip
    mv vosk-model-en-in-0.5 en-in
    rm vosk-model-en-in-0.5.zip
fi

# Hindi model
if [ ! -d "hi" ]; then
    echo "  Downloading Hindi model (1.5GB)..."
    wget -q --show-progress https://alphacephei.com/vosk/models/vosk-model-hi-0.22.zip
    unzip -q vosk-model-hi-0.22.zip
    mv vosk-model-hi-0.22 hi
    rm vosk-model-hi-0.22.zip
fi

cd ../..

# Install Ollama
echo "📦 Installing Ollama..."
if ! command -v ollama &> /dev/null; then
    curl -fsSL https://ollama.com/install.sh | sh
fi

# Pull default model
echo "📥 Pulling LLM model (this may take a while)..."
echo "   Choose your model:"
echo "   1) gpt-oss:120b-cloud (Best quality, cloud-hosted)"
echo "   2) llama3.2 (Fast, local fallback)"
echo "   3) Both (Recommended)"
read -p "   Enter choice [3]: " model_choice
model_choice=${model_choice:-3}

case $model_choice in
    1) ollama pull gpt-oss:120b-cloud ;;
    2) ollama pull llama3.2 ;;
    3) 
        ollama pull gpt-oss:120b-cloud
        ollama pull llama3.2
        ;;
    *) 
        ollama pull gpt-oss:120b-cloud
        ollama pull llama3.2
        ;;
esac

# Create Python virtual environment
echo "🐍 Setting up Python environment..."
python3.11 -m venv venv
source venv/bin/activate

# Install Python dependencies
echo "📦 Installing Python dependencies..."
pip install --upgrade pip
pip install -r requirements.txt

# Create .env file
if [ ! -f ".env" ]; then
    echo "📝 Creating .env file..."
    cp .env.example .env
    echo ""
    echo "⚠️  IMPORTANT: Edit .env and add your GEMINI_API_KEY"
    echo "   Get your API key from: https://makersuite.google.com/app/apikey"
fi

echo ""
echo "✅ Setup complete!"
echo ""
echo "Next steps:"
echo "  1. Edit .env and add your GEMINI_API_KEY"
echo "  2. Start Ollama: ollama serve"
echo "  3. Start Zeni: ./start_zeni.sh"
echo ""
echo "The server will be available at: ws://localhost:8765/voice"
