"""
Authentication module for Admin Dashboard.
Simple token-based authentication.

Credentials are loaded from environment variables:
  ADMIN_USERNAME  - default: "admin"
  ADMIN_PASSWORD  - REQUIRED: set a strong password in your .env file
"""

import hashlib
import os
import secrets
import time
from typing import Optional, Dict
from functools import wraps
from fastapi import HTTPException, Request, Depends
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials

# Credentials loaded from environment — never hardcoded
ADMIN_USERNAME = os.environ.get("ADMIN_USERNAME", "admin")
_raw_password = os.environ.get("ADMIN_PASSWORD", "")
if not _raw_password:
    raise RuntimeError(
        "ADMIN_PASSWORD environment variable is not set. "
        "Please set it in your .env file before starting the server."
    )
ADMIN_PASSWORD_HASH = hashlib.sha256(_raw_password.encode()).hexdigest()

# Session storage (in-memory, resets on server restart)
active_sessions: Dict[str, dict] = {}
SESSION_TIMEOUT = 3600 * 8  # 8 hours

security = HTTPBearer(auto_error=False)


def hash_password(password: str) -> str:
    """Hash password using SHA256."""
    return hashlib.sha256(password.encode()).hexdigest()


def verify_password(password: str) -> bool:
    """Verify password against stored hash."""
    return hash_password(password) == ADMIN_PASSWORD_HASH


def create_session(username: str) -> str:
    """Create a new session and return token."""
    token = secrets.token_urlsafe(32)
    active_sessions[token] = {
        "username": username,
        "created_at": time.time(),
        "last_activity": time.time()
    }
    return token


def verify_token(token: str) -> Optional[dict]:
    """Verify session token and return session data."""
    if token not in active_sessions:
        return None
    
    session = active_sessions[token]
    
    # Check timeout
    if time.time() - session["last_activity"] > SESSION_TIMEOUT:
        del active_sessions[token]
        return None
    
    # Update last activity
    session["last_activity"] = time.time()
    return session


def invalidate_session(token: str) -> bool:
    """Invalidate/logout a session."""
    if token in active_sessions:
        del active_sessions[token]
        return True
    return False


async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(security)
) -> dict:
    """Dependency to get current authenticated user."""
    if not credentials:
        raise HTTPException(status_code=401, detail="Not authenticated")
    
    session = verify_token(credentials.credentials)
    if not session:
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    
    return session


def require_auth(func):
    """Decorator for requiring authentication."""
    @wraps(func)
    async def wrapper(*args, **kwargs):
        request = kwargs.get('request')
        if not request:
            for arg in args:
                if isinstance(arg, Request):
                    request = arg
                    break
        
        auth_header = request.headers.get("Authorization", "")
        if not auth_header.startswith("Bearer "):
            raise HTTPException(status_code=401, detail="Not authenticated")
        
        token = auth_header.replace("Bearer ", "")
        session = verify_token(token)
        if not session:
            raise HTTPException(status_code=401, detail="Invalid or expired token")
        
        return await func(*args, **kwargs)
    
    return wrapper
