"""
Zeni LLM Engine (Groq)
Ultra-fast streaming language model integration using Groq API.
Groq provides ~200-400ms first token latency - 10x faster than local Ollama.
"""

import asyncio
import json
import time
from typing import Optional, AsyncGenerator, List, Dict, Any, Callable, Awaitable
from dataclasses import dataclass

import aiohttp

from core.config import config
from core.protocol import Language, ConversationTurn, Personality
from core.logging import get_logger, llm_latency

# Import RAG engine for FAQ search
try:
    from engines.rag import get_faq_context
    RAG_AVAILABLE = True
except ImportError:
    RAG_AVAILABLE = False
    get_faq_context = None

# Import Action engine for AI-driven actions
try:
    from engines.actions import get_action_engine, parse_action_from_response
    ACTIONS_AVAILABLE = True
except ImportError:
    ACTIONS_AVAILABLE = False
    get_action_engine = None
    parse_action_from_response = None

logger = get_logger("llm")

# Groq API Configuration
GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions"

# ============== Personality Prompts ==============
# These prompts modify how the AI responds based on personality mode

HUMAN_PERSONALITY_PROMPT = """You are Zeni, a friendly AI. Talk naturally like a friend."""

ASSISTANT_PERSONALITY_PROMPT = """You are Zeni, a professional AI assistant. Be concise and formal."""

GENERAL_SYSTEM_PROMPT = """You are Zeni, a friendly AI. You can talk about anything!"""

# Available Groq Models (sorted by speed)
GROQ_MODELS = {
    "openai/gpt-oss-120b": "GPT OSS 120B - intelligent with fast TTFT",
    "llama-3.3-70b-versatile": "Best quality, very fast",
    "llama-3.1-70b-versatile": "Great quality, very fast", 
    "llama-3.1-8b-instant": "Good quality, ultra fast",
    "mixtral-8x7b-32768": "Good for longer context",
    "gemma2-9b-it": "Fast, good for simple tasks",
}


@dataclass
class LLMResponse:
    """LLM response chunk."""
    token: str
    is_complete: bool
    full_text: Optional[str] = None


class GroqLLMEngine:
    """
    Groq LLM integration with streaming support and API key rotation.
    
    Groq is ~10x faster than local Ollama:
    - First token latency: ~200-400ms (vs 3-6s for Ollama)
    - Token generation: ~500 tokens/second
    """
    
    def __init__(self):
        # Groq API keys for rotation
        self.api_keys = config.llm.api_keys if config.llm.api_keys else []
        self.api_key_rotation = config.llm.api_key_rotation
        self._current_key_index = 0
        
        # Model configuration
        self.model = config.llm.model  # GPT-OSS for main conversation + function calling
        self.temperature = config.llm.temperature
        self.max_tokens = config.llm.max_tokens
        self.timeout = config.llm.timeout
        
        # System prompts from config
        self.system_prompt = config.system_prompt
        self.human_personality_prompt = config.human_personality_prompt
        self.assistant_personality_prompt = config.assistant_personality_prompt
        self.general_system_prompt = config.general_system_prompt
        
        # HTTP session (persistent connection)
        self._session: Optional[aiohttp.ClientSession] = None
        
        # Track key failures for smart rotation
        self._key_failures = {}
        self._key_rate_limited_until = {}
    
    async def initialize(self) -> bool:
        """Initialize the LLM engine with persistent connection."""
        try:
            # Pre-initialize RAG engine (lazy load on separate thread to not block startup)
            if RAG_AVAILABLE:
                import threading
                def init_rag():
                    try:
                        from engines.rag import get_rag_engine
                        get_rag_engine()
                        logger.info("rag_engine_pre_initialized")
                    except Exception as e:
                        logger.warning("rag_preload_failed", error=str(e))
                threading.Thread(target=init_rag, daemon=True).start()
            
            # Create persistent session with connection pooling
            connector = aiohttp.TCPConnector(
                limit=10,  # Connection pool size
                keepalive_timeout=300,  # Keep connections alive for 5 minutes
                enable_cleanup_closed=True
            )
            
            self._session = aiohttp.ClientSession(
                connector=connector,
                timeout=aiohttp.ClientTimeout(
                    total=self.timeout,
                    connect=5,  # Fast connection timeout
                    sock_read=self.timeout
                )
            )
            
            if not self.api_keys:
                logger.error("no_groq_api_keys_configured")
                return False
            
            logger.info("groq_llm_engine_initialized", 
                       model=self.model,
                       api_keys_count=len(self.api_keys),
                       key_rotation=self.api_key_rotation)
            
            # Warmup request to establish connection
            asyncio.create_task(self._warmup())
            
            return True
            
        except Exception as e:
            logger.error("llm_init_failed", error=str(e))
            return False
    
    def _get_next_api_key(self) -> str:
        """Get next API key using smart round-robin rotation."""
        if not self.api_keys:
            raise ValueError("No Groq API keys configured")
        
        current_time = time.time()
        
        # Try each key, skipping rate-limited ones
        for _ in range(len(self.api_keys)):
            key = self.api_keys[self._current_key_index]
            self._current_key_index = (self._current_key_index + 1) % len(self.api_keys)
            
            # Check if this key is rate limited
            rate_limited_until = self._key_rate_limited_until.get(key, 0)
            if current_time >= rate_limited_until:
                logger.debug("using_api_key", key_index=self._current_key_index, key_suffix=key[-8:])
                return key
        
        # All keys rate limited - use the one with shortest wait
        key = min(self.api_keys, key=lambda k: self._key_rate_limited_until.get(k, 0))
        logger.warning("all_keys_rate_limited_using", key_suffix=key[-8:])
        return key
    
    def _mark_key_rate_limited(self, key: str, retry_after: int = 60):
        """Mark an API key as rate limited."""
        self._key_rate_limited_until[key] = time.time() + retry_after
        logger.warning("api_key_rate_limited", key_suffix=key[-8:], retry_after=retry_after)
    
    def _has_vision_available(self, session_id: Optional[str]) -> bool:
        """Check if vision is available for this session."""
        if not session_id:
            return False
        try:
            from engines.vision import get_vision_engine
            vision = get_vision_engine()
            return vision._initialized
        except Exception:
            return False
    
    async def _execute_vision_tool(
        self, 
        session_id: str, 
        user_query: str,
        request_image_fn: Optional[Callable[[], Awaitable[None]]] = None
    ) -> str:
        """
        Execute vision analysis - uses PRE-ANALYSIS if available (INSTANT!).
        
        OPTIMIZED FLOW:
        1. Image arrives with mic press -> pre-analysis starts in parallel with ASR
        2. User finishes speaking -> pre-analysis likely already complete
        3. This function just retrieves cached result = INSTANT!
        """
        try:
            from engines.vision import get_vision_engine
            vision = get_vision_engine()
            
            if not vision._initialized:
                return "Vision system is not available right now."
            
            # FAST PATH: Check for pre-analysis result
            pre_analysis = vision.get_pre_analysis(session_id, max_age_seconds=30.0)
            if pre_analysis:
                logger.info("using_pre_analysis_INSTANT", session_id=session_id[:8])
                return pre_analysis
            
            # Check if pre-analysis is still running - WAIT for it! (longer timeout for detailed analysis)
            if vision._pre_analysis_task and not vision._pre_analysis_task.done():
                logger.info("waiting_for_pre_analysis", session_id=session_id[:8])
                pre_analysis = await vision.wait_for_pre_analysis(session_id, timeout=8.0)
                if pre_analysis:
                    logger.info("pre_analysis_completed_while_waiting", session_id=session_id[:8], result_len=len(pre_analysis))
                    return pre_analysis
            
            # Check if we have image but no pre-analysis (do fresh analysis)
            image_base64 = vision.get_present_image(session_id, max_age_seconds=30.0)
            if image_base64:
                logger.info("have_image_doing_fresh_analysis", session_id=session_id[:8])
                result = await vision.analyze(session_id, user_query, image_base64)
                return result if result else "Could not analyze the image."
            
            # No image at all - request from client (fast timeout to not break TTS)
            logger.warning("no_image_available_requesting", session_id=session_id[:8])
            if request_image_fn:
                image_base64 = await vision.request_and_wait(session_id, request_image_fn, timeout=2.0)
                if image_base64:
                    result = await vision.analyze(session_id, user_query, image_base64)
                    return result if result else "Could not analyze the image."
            
            # Final fallback - no image available
            return "I cannot see you right now. Please make sure camera permission is enabled and try again."
        except Exception as e:
            logger.warning("vision_tool_failed", error=str(e))
            return f"Vision analysis failed: {str(e)}"
    
    def _get_tools(self, session_id: Optional[str], robot_enabled: bool = False) -> Optional[List[dict]]:
        """Get available tools for function calling."""
        tools = []
        
        # Vision tool - if vision engine is initialized
        try:
            from engines.vision import get_vision_engine
            vision = get_vision_engine()
            if vision._initialized:
                logger.info("tools_provided", tool="look_with_eyes", has_actual_image=vision.has_image(session_id) if session_id else False)
                tools.append({
                    "type": "function",
                    "function": {
                        "name": "look_with_eyes",
                        "description": "Use your eyes to see. You have vision - you can naturally see the person in front of you and their surroundings. Use this to look at them, observe what they're doing, see objects around them, read documents they hold up, or notice anything visual. Just like a human would use their eyes.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "observation_focus": {
                                    "type": "string",
                                    "description": "What to focus on, e.g., 'the person', 'what they are holding', 'their surroundings', 'the document'"
                                }
                            },
                            "required": ["observation_focus"]
                        }
                    }
                })
        except Exception:
            pass
        
        # Robot control tool - if robot is connected
        if robot_enabled:
            logger.info("tools_provided", tool="control_robot")
            tools.append({
                "type": "function",
                "function": {
                    "name": "control_robot",
                    "description": "Control my physical robot body. I have 2 wheels - to move sideways, first turn (left/right) then move forward. I am connected by wire from behind so NO spinning. When user says 'come closer' use forward, 'go back' use backward, 'turn left/right' to rotate in place. For 'move left': first turn left, then forward. Can specify duration in milliseconds (e.g., 3000 for 3 seconds).",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "action": {
                                "type": "string",
                                "enum": ["forward", "backward", "left", "right", "stop"],
                                "description": "Movement action: forward/backward to move straight, left/right to rotate in place (turn), stop to halt"
                            },
                            "duration": {
                                "type": "integer",
                                "description": "Duration in milliseconds (100-5000). Default 1000ms. For longer movements like '3 seconds' use 3000.",
                                "minimum": 100,
                                "maximum": 5000
                            },
                            "speed": {
                                "type": "integer",
                                "description": "Speed percentage (10-100). Default 50 for safety",
                                "minimum": 10,
                                "maximum": 100
                            }
                        },
                        "required": ["action"]
                    }
                }
            })
        
        return tools if tools else None
    
    def _build_messages(
        self,
        user_message: str,
        conversation_history: List[ConversationTurn],
        language: Language = Language.ENGLISH,
        personality: Personality = Personality.ASSISTANT,
        session_id: Optional[str] = None
    ) -> List[dict]:
        """
        Build messages array for Groq API (OpenAI format).
        
        Optimizations:
        - Limit history to 3 turns for faster processing
        - RAG context injection for accurate FAQ answers
        - Action capabilities for AI-driven decisions
        - Personality mode for human-like or assistant responses
        """
        messages = []
        
        # GENERAL mode: Skip college system prompt and RAG - pure AI chat
        if personality == Personality.GENERAL:
            system = self.general_system_prompt
            if language == Language.HINDI:
                system += "\nUser speaks Hindi, respond in Hindi."
            
            messages.append({"role": "system", "content": system})
            
            # Add conversation history (limit to recent turns)
            recent_turns = conversation_history[-3:] if len(conversation_history) > 3 else conversation_history
            for turn in recent_turns:
                role = "user" if turn.role == "user" else "assistant"
                messages.append({"role": role, "content": turn.content})
            
            messages.append({"role": "user", "content": user_message})
            return messages
        
        # ASSISTANT/HUMAN mode: Use college system prompt with RAG
        system = self.system_prompt
        
        # Add personality-specific instructions
        if personality == Personality.HUMAN:
            system += f"\n{self.human_personality_prompt}"
        else:
            system += f"\n{self.assistant_personality_prompt}"
        
        if language == Language.HINDI:
            system += "\nRespond in Hindi when the user speaks Hindi. Keep the same personality traits in Hindi."
        
        # RAG: Search FAQ and inject relevant context
        if RAG_AVAILABLE and get_faq_context:
            try:
                faq_context = get_faq_context(user_message, top_k=3)
                if faq_context:
                    system += f"\n\n=== VERIFIED GEHU REFERENCE DATA (USE ONLY THIS FOR FACTUAL ANSWERS) ===\n{faq_context}\n=== END REFERENCE DATA ===\n\nREMEMBER: For ANY factual college question (names, fees, dates, positions), use ONLY the data above. If it's not there, say 'I don't have that specific information.'"
                    logger.info("rag_context_injected", context_length=len(faq_context))
            except Exception as e:
                logger.warning("rag_search_failed", error=str(e))
        
        # Actions: Add available actions for AI reasoning
        if ACTIONS_AVAILABLE and get_action_engine:
            try:
                action_engine = get_action_engine()
                actions_prompt = action_engine.get_available_actions_prompt()
                system += f"\n{actions_prompt}"
                logger.debug("actions_prompt_injected")
            except Exception as e:
                logger.warning("actions_prompt_failed", error=str(e))
        
        # Vision: Tool available for on-demand analysis (via function calling)
        # No background context needed - LLM will call analyze_what_user_sees when needed
        
        messages.append({
            "role": "system",
            "content": system
        })
        
        # Add conversation history (last 3 turns only for speed - 40% less tokens)
        for turn in conversation_history[-3:]:
            messages.append({
                "role": turn.role,
                "content": turn.content
            })
        
        # Add current user message
        messages.append({
            "role": "user",
            "content": user_message
        })
        
        return messages
    
    async def generate_stream(
        self,
        user_message: str,
        conversation_history: List[ConversationTurn],
        language: Language = Language.ENGLISH,
        cancel_event: Optional[asyncio.Event] = None,
        personality: Personality = Personality.ASSISTANT,
        session_id: Optional[str] = None,
        request_image_fn: Optional[Callable[[], Awaitable[None]]] = None,
        robot_enabled: bool = False,
        robot_command_fn: Optional[Callable[[str, int, int], Awaitable[None]]] = None
    ) -> AsyncGenerator[LLMResponse, None]:
        """
        Stream LLM response tokens from Groq with function calling support.
        
        Flow:
        1. Check if tools available (vision, robot)
        2. If tools: quick non-streaming call to check if tool needed
        3. If tool call: execute tool, then stream with context
        4. If no tool: stream directly
        
        Args:
            user_message: The user's message
            conversation_history: Previous conversation turns
            language: Detected language for context
            cancel_event: Event to signal cancellation
            personality: AI personality mode (assistant or human)
            session_id: Session ID for vision context (optional)
            request_image_fn: Async function to request image from client for vision
            robot_enabled: Whether robot control is enabled
            robot_command_fn: Async function to send robot commands (action, duration, speed)
        
        Yields:
            LLMResponse chunks with tokens
        """
        if not self._session:
            logger.error("llm_session_not_initialized")
            return
        
        start_time = time.perf_counter()
        logger.info("groq_generate_stream_called", 
                   user_message=user_message[:50], 
                   language=language.value,
                   personality=personality.value,
                   robot_enabled=robot_enabled)
        
        # Get API key for this request
        api_key = self._get_next_api_key()
        
        # Build base messages
        messages = self._build_messages(user_message, conversation_history, language, personality, session_id)
        
        # Check for tools (vision and robot function calling)
        tools = self._get_tools(session_id, robot_enabled=robot_enabled)
        tool_result = None
        
        if tools:
            # Quick non-streaming call to check if model wants to use a tool
            tool_result = await self._check_tool_call(
                messages, tools, api_key, session_id, user_message, 
                request_image_fn, robot_command_fn
            )
            if tool_result:
                # Inject tool result as context
                messages[-1]["content"] = f"{user_message}\n\n{tool_result}"
                logger.info("tool_result_injected", result_len=len(tool_result))
        
        # Now stream the actual response
        async for chunk in self._stream_response(messages, api_key, cancel_event, start_time):
            yield chunk
    
    async def _check_tool_call(
        self, 
        messages: List[dict], 
        tools: List[dict], 
        api_key: str,
        session_id: str,
        user_message: str,
        request_image_fn: Optional[Callable[[], Awaitable[None]]] = None,
        robot_command_fn: Optional[Callable[[str, int, int], Awaitable[None]]] = None
    ) -> Optional[str]:
        """Quick non-streaming call to check if model wants to use a tool. LLM decides when to use vision or robot."""
        try:
            # Build a focused tool-check message with vision and robot context
            tool_check_messages = [
                {
                    "role": "system",
                    "content": """You are Zeni, an AI with EYES and a physical BODY (robot).

VISION: You have eyesight. When the user asks about:
- How they look, their appearance, outfit, or style
- Checking something out or giving visual feedback
- Reading something, looking at a document or object
- What you can see, what's in front of you
You MUST call the 'look_with_eyes' function.

ROBOT BODY: You can MOVE physically. When the user asks you to:
- Come closer, approach them, come here
- Move away, go back, step back
- Turn around, spin, rotate
- Move forward, backward, left, right
- Any physical movement
You MUST call the 'control_robot' function.

Use tools naturally. Never refuse when tools match the request."""
                },
                {
                    "role": "user",
                    "content": user_message
                }
            ]
            
            logger.info("tool_check_starting", user_msg=user_message[:50])
            
            payload = {
                "model": self.model,
                "messages": tool_check_messages,
                "temperature": 0.0,  # Zero temp for deterministic tool decision
                "max_tokens": 200,   # Enough for complete tool call JSON (was 50 - caused truncation)
                "tools": tools,
                "tool_choice": "auto"
            }
            
            headers = {
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json"
            }
            
            async with self._session.post(
                GROQ_API_URL,
                json=payload,
                headers=headers,
                timeout=aiohttp.ClientTimeout(total=5)  # Quick timeout
            ) as response:
                if response.status != 200:
                    error_text = await response.text()
                    logger.warning("tool_check_api_error", status=response.status, error=error_text[:100])
                    return None
                
                result = await response.json()
                choices = result.get("choices", [])
                
                if not choices:
                    logger.debug("tool_check_no_choices")
                    return None
                
                message = choices[0].get("message", {})
                tool_calls = message.get("tool_calls", [])
                content = message.get("content", "")
                
                logger.info("tool_check_result", 
                           has_tool_calls=bool(tool_calls), 
                           content_preview=content[:50] if content else "none")
                
                if tool_calls:
                    # Model wants to use a tool
                    tool_call = tool_calls[0]
                    function_name = tool_call.get("function", {}).get("name", "")
                    function_args_str = tool_call.get("function", {}).get("arguments", "{}")
                    
                    try:
                        function_args = json.loads(function_args_str)
                    except:
                        function_args = {}
                    
                    if function_name in ("analyze_what_user_sees", "look_with_eyes"):
                        logger.info("vision_eyes_used", session_id=session_id[:8] if session_id else "none")
                        # Execute vision analysis - pass the image request callback
                        return await self._execute_vision_tool(session_id, user_message, request_image_fn)
                    
                    elif function_name == "control_robot" and robot_command_fn:
                        action = function_args.get("action", "stop")
                        duration = function_args.get("duration", 500)
                        speed = function_args.get("speed", 50)
                        logger.info("robot_control_called", action=action, duration=duration, speed=speed)
                        
                        # Send robot command
                        await robot_command_fn(action, duration, speed)
                        
                        # Return context for LLM response
                        return f"[Robot action executed: {action} for {duration}ms at {speed}% speed]"
                
                return None
                
        except asyncio.TimeoutError:
            logger.warning("tool_check_failed", error="Timeout (5s)")
            return None
        except aiohttp.ClientError as e:
            logger.warning("tool_check_failed", error=f"HTTP error: {type(e).__name__}: {str(e)}")
            return None
        except Exception as e:
            logger.warning("tool_check_failed", error=f"{type(e).__name__}: {str(e) or 'no message'}")
            return None
    
    async def _stream_response(
        self,
        messages: List[dict],
        api_key: str,
        cancel_event: Optional[asyncio.Event],
        start_time: float
    ) -> AsyncGenerator[LLMResponse, None]:
        """Stream the LLM response."""
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": self.temperature,
            "max_tokens": self.max_tokens,
            "stream": True,
            "stop": None
        }
        
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json"
        }
        
        full_response = []
        first_token_logged = False
        
        try:
            with llm_latency.track("generate"):
                async with self._session.post(
                    GROQ_API_URL,
                    json=payload,
                    headers=headers
                ) as response:
                    
                    # Handle rate limiting
                    if response.status == 429:
                        retry_after = int(response.headers.get("Retry-After", 60))
                        self._mark_key_rate_limited(api_key, retry_after)
                        logger.warning("rate_limited", retry_after=retry_after)
                        return
                    
                    if response.status != 200:
                        error_text = await response.text()
                        logger.error("groq_api_error", status=response.status, error=error_text[:200])
                        return
                    
                    # Stream response
                    async for line in response.content:
                        # Check for cancellation
                        if cancel_event and cancel_event.is_set():
                            logger.info("llm_generation_cancelled")
                            return
                        
                        line = line.decode('utf-8').strip()
                        
                        if not line or line == "data: [DONE]":
                            continue
                        
                        if line.startswith("data: "):
                            try:
                                data = json.loads(line[6:])
                                
                                choices = data.get("choices", [])
                                if choices:
                                    delta = choices[0].get("delta", {})
                                    content = delta.get("content", "")
                                    finish_reason = choices[0].get("finish_reason")
                                    
                                    if content:
                                        full_response.append(content)
                                        
                                        if not first_token_logged:
                                            first_token_time = (time.perf_counter() - start_time) * 1000
                                            logger.info("groq_first_token", 
                                                       latency_ms=round(first_token_time, 2))
                                            first_token_logged = True
                                        
                                        yield LLMResponse(
                                            token=content,
                                            is_complete=False
                                        )
                                    
                                    if finish_reason:
                                        if finish_reason == "content_filter":
                                            logger.warning("llm_content_filtered")
                                            fallback = "I'm sorry, I cannot respond to that. Is there something else I can help you with?"
                                            yield LLMResponse(token=fallback, is_complete=False)
                                            yield LLMResponse(token="", is_complete=True, full_text=fallback)
                                            return
                                        elif finish_reason in ("stop", "length"):
                                            final_text = "".join(full_response).strip()
                                            total_time = (time.perf_counter() - start_time) * 1000
                                            logger.info("groq_generation_complete", 
                                                       tokens=len(full_response),
                                                       total_ms=round(total_time, 2))
                                            yield LLMResponse(token="", is_complete=True, full_text=final_text)
                                            return
                                        
                            except json.JSONDecodeError:
                                continue
                    
                    # Exit without stop signal
                    if full_response:
                        final_text = "".join(full_response).strip()
                        yield LLMResponse(token="", is_complete=True, full_text=final_text)
                    else:
                        logger.warning("llm_no_tokens_received")
                        fallback = "I'm sorry, I cannot respond to that. Is there something else I can help you with?"
                        yield LLMResponse(token=fallback, is_complete=False)
                        yield LLMResponse(token="", is_complete=True, full_text=fallback)
        
        except asyncio.TimeoutError:
            logger.error("groq_timeout", timeout=self.timeout)
            if full_response:
                yield LLMResponse(token="", is_complete=True, full_text="".join(full_response).strip())
        
        except asyncio.CancelledError:
            logger.info("llm_stream_cancelled")
            raise
        
        except aiohttp.ClientError as e:
            logger.error("groq_client_error", error=str(e))
        
        except Exception as e:
            logger.error("groq_error", error=str(e), error_type=type(e).__name__)
    
    async def generate(
        self,
        user_message: str,
        conversation_history: List[ConversationTurn],
        language: Language = Language.ENGLISH
    ) -> Optional[str]:
        """
        Non-streaming generation (for simple use cases).
        """
        full_text = None
        async for response in self.generate_stream(user_message, conversation_history, language):
            if response.is_complete:
                full_text = response.full_text
        return full_text
    
    async def health_check(self) -> bool:
        """Check if Groq API is accessible."""
        if not self._session or not self.api_keys:
            return False
        
        try:
            api_key = self.api_keys[0]
            headers = {"Authorization": f"Bearer {api_key}"}
            
            async with self._session.get(
                "https://api.groq.com/openai/v1/models",
                headers=headers,
                timeout=aiohttp.ClientTimeout(total=5)
            ) as response:
                return response.status == 200
        except Exception:
            return False
    
    async def _warmup(self) -> None:
        """
        Send a minimal request to establish connection and warm up.
        This reduces latency for the first real request.
        """
        if not self._session or not self.api_keys:
            return
        
        logger.info("groq_warmup_starting")
        
        try:
            api_key = self.api_keys[0]
            headers = {
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json"
            }
            
            # Minimal request just to warm up connection
            payload = {
                "model": self.model,
                "messages": [{"role": "user", "content": "Hi"}],
                "max_tokens": 1,
                "stream": False
            }
            
            start = time.perf_counter()
            async with self._session.post(
                GROQ_API_URL,
                json=payload,
                headers=headers,
                timeout=aiohttp.ClientTimeout(total=10)
            ) as response:
                if response.status == 200:
                    duration = (time.perf_counter() - start) * 1000
                    logger.info("groq_warmup_complete", duration_ms=round(duration, 2))
                else:
                    logger.warning("groq_warmup_failed", status=response.status)
                    
        except Exception as e:
            logger.warning("groq_warmup_error", error=str(e))
    
    async def shutdown(self) -> None:
        """Shutdown the LLM engine."""
        if self._session:
            await self._session.close()
            self._session = None
        logger.info("llm_engine_shutdown")


# Alias for backwards compatibility
LLMEngine = GroqLLMEngine


async def create_llm_engine() -> GroqLLMEngine:
    """Factory function to create and initialize LLM engine."""
    engine = GroqLLMEngine()
    await engine.initialize()
    return engine
