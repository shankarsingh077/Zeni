"""
Zeni Logging Module
Structured logging with performance tracking.
"""

import logging
import sys
import time
from functools import wraps
from typing import Any, Callable
from contextlib import contextmanager

import structlog


def setup_logging(log_level: str = "WARNING", production: bool = True) -> None:
    """Configure structured logging for Zeni.
    
    Args:
        log_level: Logging level (DEBUG, INFO, WARNING, ERROR)
        production: If True, use minimal processors for performance
    """
    level = getattr(logging, log_level.upper(), logging.WARNING)
    
    if production and level >= logging.WARNING:
        # Production mode: minimal processors for speed
        processors = [
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="%H:%M:%S"),
            structlog.dev.ConsoleRenderer(colors=False, pad_event=0)
        ]
    else:
        # Debug mode: full processors
        processors = [
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.StackInfoRenderer(),
            structlog.dev.set_exc_info,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.dev.ConsoleRenderer(colors=True)
        ]
    
    # Configure structlog
    structlog.configure(
        processors=processors,
        wrapper_class=structlog.make_filtering_bound_logger(level),
        context_class=dict,
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=True,
    )
    
    # Also configure standard logging
    logging.basicConfig(
        format="%(asctime)s - %(levelname)s - %(message)s" if production else 
               "%(asctime)s - %(name)s - %(levelname)s - %(message)s",
        level=level,
        stream=sys.stdout,
        force=True  # Override any existing config
    )


def get_logger(name: str = "zeni") -> structlog.BoundLogger:
    """Get a logger instance."""
    return structlog.get_logger(name)


class LatencyTracker:
    """Track latency for performance monitoring."""
    
    def __init__(self, name: str):
        self.name = name
        self.logger = get_logger("latency")
        self.measurements: list[float] = []
        self.max_measurements = 1000  # Rolling window
    
    @contextmanager
    def track(self, operation: str = ""):
        """Context manager to track operation latency."""
        start = time.perf_counter()
        try:
            yield
        finally:
            elapsed_ms = (time.perf_counter() - start) * 1000
            self.measurements.append(elapsed_ms)
            if len(self.measurements) > self.max_measurements:
                self.measurements.pop(0)
            
            self.logger.debug(
                "operation_latency",
                component=self.name,
                operation=operation,
                latency_ms=round(elapsed_ms, 2)
            )
    
    def get_stats(self) -> dict[str, float]:
        """Get latency statistics."""
        if not self.measurements:
            return {"p50": 0, "p95": 0, "p99": 0, "avg": 0}
        
        sorted_data = sorted(self.measurements)
        n = len(sorted_data)
        
        return {
            "p50": sorted_data[int(n * 0.5)] if n > 0 else 0,
            "p95": sorted_data[int(n * 0.95)] if n > 1 else sorted_data[-1],
            "p99": sorted_data[int(n * 0.99)] if n > 1 else sorted_data[-1],
            "avg": sum(sorted_data) / n if n > 0 else 0,
            "count": n
        }


def track_latency(tracker: LatencyTracker, operation: str = ""):
    """Decorator to track function latency."""
    def decorator(func: Callable) -> Callable:
        @wraps(func)
        async def async_wrapper(*args, **kwargs):
            with tracker.track(operation or func.__name__):
                return await func(*args, **kwargs)
        
        @wraps(func)
        def sync_wrapper(*args, **kwargs):
            with tracker.track(operation or func.__name__):
                return func(*args, **kwargs)
        
        if asyncio_iscoroutinefunction(func):
            return async_wrapper
        return sync_wrapper
    return decorator


def asyncio_iscoroutinefunction(func: Callable) -> bool:
    """Check if function is async."""
    import asyncio
    return asyncio.iscoroutinefunction(func)


# Global latency trackers
asr_latency = LatencyTracker("asr")
llm_latency = LatencyTracker("llm")
tts_latency = LatencyTracker("tts")
pipeline_latency = LatencyTracker("pipeline")
