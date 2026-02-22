"""
Admin API Routes for Zeni Voice AI.
Handles FAQ management and placement photo uploads.
"""

import os
import json
import shutil
import uuid
from datetime import datetime
from typing import List, Optional
from pathlib import Path

from fastapi import APIRouter, HTTPException, Depends, UploadFile, File, Form
from fastapi.responses import HTMLResponse, FileResponse
from pydantic import BaseModel

from .auth import (
    verify_password, create_session, invalidate_session,
    get_current_user, verify_token
)

# Base paths
BASE_DIR = Path(__file__).parent.parent
DATA_DIR = BASE_DIR / "data"
FAQ_FILE = DATA_DIR / "faq.json"
PLACEMENT_DIR = BASE_DIR.parent / "Placement"

admin_router = APIRouter(prefix="/admin", tags=["admin"])


# ============== Pydantic Models ==============

class LoginRequest(BaseModel):
    username: str
    password: str


class FAQItem(BaseModel):
    id: Optional[str] = None
    question: str
    answer: str
    category: str


class FAQUpdateRequest(BaseModel):
    question: str
    answer: str
    category: str


# ============== Helper Functions ==============

def load_faqs() -> List[dict]:
    """Load FAQs from JSON file."""
    try:
        with open(FAQ_FILE, 'r', encoding='utf-8') as f:
            return json.load(f)
    except Exception as e:
        print(f"Error loading FAQs: {e}")
        return []


def save_faqs(faqs: List[dict]) -> bool:
    """Save FAQs to JSON file."""
    try:
        with open(FAQ_FILE, 'w', encoding='utf-8') as f:
            json.dump(faqs, f, indent=2, ensure_ascii=False)
        return True
    except Exception as e:
        print(f"Error saving FAQs: {e}")
        return False


def rebuild_rag_index():
    """Trigger RAG index rebuild after FAQ changes."""
    try:
        from engines.rag import get_rag_engine
        rag = get_rag_engine()
        return rag.rebuild_index()
    except Exception as e:
        print(f"Warning: Could not rebuild RAG index: {e}")
        return {"success": False, "message": str(e)}


# ============== Auth Routes ==============

@admin_router.post("/login")
async def login(request: LoginRequest):
    """Admin login endpoint."""
    if request.username != "admin":
        raise HTTPException(status_code=401, detail="Invalid credentials")
    
    if not verify_password(request.password):
        raise HTTPException(status_code=401, detail="Invalid credentials")
    
    token = create_session(request.username)
    return {
        "success": True,
        "token": token,
        "message": "Login successful"
    }


@admin_router.post("/logout")
async def logout(user: dict = Depends(get_current_user)):
    """Admin logout endpoint."""
    # Token is invalidated by the auth system
    return {"success": True, "message": "Logged out successfully"}


@admin_router.get("/verify")
async def verify_auth(user: dict = Depends(get_current_user)):
    """Verify if current token is valid."""
    return {
        "success": True,
        "username": user["username"]
    }


# ============== FAQ Routes ==============

@admin_router.get("/faqs")
async def get_faqs(user: dict = Depends(get_current_user)):
    """Get all FAQs."""
    faqs = load_faqs()
    return {
        "success": True,
        "faqs": faqs,
        "total": len(faqs)
    }


@admin_router.get("/faqs/{faq_id}")
async def get_faq(faq_id: str, user: dict = Depends(get_current_user)):
    """Get a specific FAQ by ID."""
    faqs = load_faqs()
    for faq in faqs:
        if faq["id"] == faq_id:
            return {"success": True, "faq": faq}
    raise HTTPException(status_code=404, detail="FAQ not found")


@admin_router.post("/faqs")
async def create_faq(faq: FAQItem, user: dict = Depends(get_current_user)):
    """Create a new FAQ."""
    faqs = load_faqs()
    
    # Generate new ID
    max_id = max([int(f["id"]) for f in faqs], default=0)
    new_id = str(max_id + 1)
    
    new_faq = {
        "id": new_id,
        "question": faq.question,
        "answer": faq.answer,
        "category": faq.category
    }
    
    faqs.append(new_faq)
    
    if save_faqs(faqs):
        rebuild_rag_index()
        return {"success": True, "faq": new_faq, "message": "FAQ created successfully"}
    
    raise HTTPException(status_code=500, detail="Failed to save FAQ")


@admin_router.put("/faqs/{faq_id}")
async def update_faq(faq_id: str, faq_update: FAQUpdateRequest, user: dict = Depends(get_current_user)):
    """Update an existing FAQ."""
    faqs = load_faqs()
    
    for i, faq in enumerate(faqs):
        if faq["id"] == faq_id:
            faqs[i]["question"] = faq_update.question
            faqs[i]["answer"] = faq_update.answer
            faqs[i]["category"] = faq_update.category
            
            if save_faqs(faqs):
                rebuild_rag_index()
                return {"success": True, "faq": faqs[i], "message": "FAQ updated successfully"}
            
            raise HTTPException(status_code=500, detail="Failed to save FAQ")
    
    raise HTTPException(status_code=404, detail="FAQ not found")


@admin_router.delete("/faqs/{faq_id}")
async def delete_faq(faq_id: str, user: dict = Depends(get_current_user)):
    """Delete an FAQ."""
    faqs = load_faqs()
    
    for i, faq in enumerate(faqs):
        if faq["id"] == faq_id:
            deleted_faq = faqs.pop(i)
            
            if save_faqs(faqs):
                rebuild_rag_index()
                return {"success": True, "message": "FAQ deleted successfully", "deleted": deleted_faq}
            
            raise HTTPException(status_code=500, detail="Failed to delete FAQ")
    
    raise HTTPException(status_code=404, detail="FAQ not found")


@admin_router.get("/faq-categories")
async def get_categories(user: dict = Depends(get_current_user)):
    """Get all unique FAQ categories."""
    faqs = load_faqs()
    categories = list(set(faq.get("category", "general") for faq in faqs))
    categories.sort()
    return {"success": True, "categories": categories}


@admin_router.post("/apply-changes")
async def apply_changes(user: dict = Depends(get_current_user)):
    """
    Apply FAQ changes by rebuilding the RAG index.
    This makes the FAQ changes effective without server restart.
    """
    result = rebuild_rag_index()
    if result.get("success"):
        return {
            "success": True,
            "message": "Changes applied successfully! RAG index rebuilt.",
            "details": result
        }
    raise HTTPException(status_code=500, detail=result.get("message", "Failed to apply changes"))


# ============== Placement Photo Routes ==============

@admin_router.get("/placements")
async def get_placements(user: dict = Depends(get_current_user)):
    """Get list of placement photos."""
    if not PLACEMENT_DIR.exists():
        PLACEMENT_DIR.mkdir(parents=True, exist_ok=True)
    
    photos = []
    for file in PLACEMENT_DIR.iterdir():
        if file.is_file() and file.suffix.lower() in ['.jpg', '.jpeg', '.png', '.gif', '.webp']:
            photos.append({
                "name": file.name,
                "path": f"/admin/placements/file/{file.name}",
                "size": file.stat().st_size,
                "modified": datetime.fromtimestamp(file.stat().st_mtime).isoformat()
            })
    
    return {"success": True, "photos": photos, "total": len(photos)}


@admin_router.get("/placements/file/{filename}")
async def get_placement_file(filename: str):
    """Serve a placement photo file (public access for display)."""
    file_path = PLACEMENT_DIR / filename
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="File not found")
    
    return FileResponse(file_path)


@admin_router.post("/placements/upload")
async def upload_placement(
    file: UploadFile = File(...),
    user: dict = Depends(get_current_user)
):
    """Upload a new placement photo."""
    # Validate file type
    allowed_types = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
    if file.content_type not in allowed_types:
        raise HTTPException(status_code=400, detail="Invalid file type. Allowed: JPEG, PNG, GIF, WEBP")
    
    # Ensure directory exists
    PLACEMENT_DIR.mkdir(parents=True, exist_ok=True)
    
    # Save file
    file_path = PLACEMENT_DIR / file.filename
    
    try:
        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)
        
        return {
            "success": True,
            "message": "Photo uploaded successfully",
            "filename": file.filename,
            "path": f"/admin/placements/file/{file.filename}"
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to upload: {str(e)}")


@admin_router.delete("/placements/{filename}")
async def delete_placement(filename: str, user: dict = Depends(get_current_user)):
    """Delete a placement photo. Cannot delete the last remaining photo."""
    file_path = PLACEMENT_DIR / filename
    
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="File not found")
    
    # Count existing photos - prevent deleting the last one
    existing_photos = list(PLACEMENT_DIR.glob("*"))
    image_extensions = {".jpg", ".jpeg", ".png", ".gif", ".webp"}
    photo_count = sum(1 for f in existing_photos if f.suffix.lower() in image_extensions)
    
    if photo_count <= 1:
        raise HTTPException(
            status_code=400, 
            detail="Cannot delete the last photo. At least one placement photo must remain."
        )
    
    try:
        file_path.unlink()
        return {"success": True, "message": "Photo deleted successfully"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to delete: {str(e)}")


# ============== Dashboard Page ==============

@admin_router.get("/", response_class=HTMLResponse)
async def admin_dashboard():
    """Serve the admin dashboard HTML page."""
    html_path = Path(__file__).parent / "static" / "index.html"
    if html_path.exists():
        return HTMLResponse(content=html_path.read_text(), status_code=200)
    return HTMLResponse(content="<h1>Admin Dashboard</h1><p>Static files not found.</p>", status_code=200)
