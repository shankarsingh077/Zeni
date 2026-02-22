"""
RAG Engine for GEHU FAQ Search
Uses intfloat/multilingual-e5-small on MPS (Apple Silicon GPU)
Supports Hindi and English queries matching FAQ data
"""

import json
import os
import time
import hashlib
from typing import List, Dict, Optional
import chromadb
from chromadb.config import Settings
import torch
from sentence_transformers import SentenceTransformer

# Paths
DATA_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "data")
FAQ_PATH = os.path.join(DATA_DIR, "faq.json")
CHROMA_PATH = os.path.join(DATA_DIR, "chroma_db")
FAQ_HASH_PATH = os.path.join(DATA_DIR, ".faq_hash")  # Store hash of FAQ content

# Configuration
EMBEDDING_MODEL = "intfloat/multilingual-e5-small"
COLLECTION_NAME = "gehu_faq"
TOP_K = 3


class RAGEngine:
    """RAG Engine with multilingual embeddings for GEHU FAQ search"""
    
    _instance = None
    _initialized = False
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance
    
    def __init__(self):
        if RAGEngine._initialized:
            return
            
        print(f"\n{'='*50}")
        print("🔍 Initializing RAG Engine")
        print(f"{'='*50}")
        
        start_time = time.time()
        
        # Detect device - prefer MPS for Apple Silicon
        if torch.backends.mps.is_available():
            self.device = "mps"
            print("✅ Using MPS (Apple Silicon GPU)")
        elif torch.cuda.is_available():
            self.device = "cuda"
            print("✅ Using CUDA GPU")
        else:
            self.device = "cpu"
            print("⚠️ Using CPU (no GPU detected)")
        
        # Load embedding model
        print(f"📥 Loading {EMBEDDING_MODEL}...")
        model_start = time.time()
        self.model = SentenceTransformer(EMBEDDING_MODEL, device=self.device)
        print(f"   Model loaded in {(time.time() - model_start)*1000:.0f}ms")
        
        # Initialize ChromaDB
        print("📦 Initializing ChromaDB...")
        self.chroma_client = chromadb.PersistentClient(
            path=CHROMA_PATH,
            settings=Settings(anonymized_telemetry=False)
        )
        
        # Check if collection needs to be created/updated
        self._setup_collection()
        
        total_time = (time.time() - start_time) * 1000
        print(f"\n✅ RAG Engine ready in {total_time:.0f}ms")
        print(f"{'='*50}\n")
        
        RAGEngine._initialized = True
    
    def _setup_collection(self):
        """Setup ChromaDB collection with FAQ data"""
        faq_data = self._load_faq()
        current_hash = self._get_faq_hash()
        stored_hash = self._get_stored_hash()
        
        # Check if FAQ content has changed (by comparing hashes)
        faq_changed = current_hash != stored_hash
        
        if faq_changed:
            print(f"   ⚠️ FAQ content changed, rebuilding embeddings...")
            # Delete old collection if exists
            try:
                self.chroma_client.delete_collection(COLLECTION_NAME)
            except Exception:
                pass  # Collection might not exist
            self._create_collection(faq_data)
            self._save_hash(current_hash)
        else:
            try:
                self.collection = self.chroma_client.get_collection(COLLECTION_NAME)
                count = self.collection.count()
                print(f"   Using existing collection ({count} items, hash unchanged)")
            except Exception:
                # Collection doesn't exist, create it
                self._create_collection(faq_data)
                self._save_hash(current_hash)
    
    def _get_faq_hash(self) -> str:
        """Calculate MD5 hash of FAQ file content"""
        with open(FAQ_PATH, 'rb') as f:
            return hashlib.md5(f.read()).hexdigest()
    
    def _get_stored_hash(self) -> str:
        """Get previously stored FAQ hash"""
        try:
            with open(FAQ_HASH_PATH, 'r') as f:
                return f.read().strip()
        except FileNotFoundError:
            return ""
    
    def _save_hash(self, hash_value: str):
        """Save current FAQ hash"""
        with open(FAQ_HASH_PATH, 'w') as f:
            f.write(hash_value)
    
    def _load_faq(self) -> List[Dict]:
        """Load FAQ data from JSON file"""
        with open(FAQ_PATH, 'r', encoding='utf-8') as f:
            return json.load(f)
    
    def _create_collection(self, faq_data: List[Dict]):
        """Create ChromaDB collection and embed FAQ data"""
        print(f"   Creating collection with {len(faq_data)} FAQ items...")
        
        # Create collection
        self.collection = self.chroma_client.create_collection(
            name=COLLECTION_NAME,
            metadata={"hnsw:space": "cosine"}
        )
        
        # Prepare documents for embedding
        # For e5 models, we need to prefix queries with "query: " and documents with "passage: "
        documents = []
        ids = []
        metadatas = []
        
        for item in faq_data:
            # Combine question and answer for better retrieval
            doc_text = f"passage: {item['question']} {item['answer']}"
            documents.append(doc_text)
            ids.append(item['id'])
            metadatas.append({
                "question": item['question'],
                "answer": item['answer'],
                "category": item.get('category', 'general')
            })
        
        # Generate embeddings
        print("   Generating embeddings...")
        embed_start = time.time()
        embeddings = self.model.encode(documents, show_progress_bar=True)
        print(f"   Embeddings generated in {(time.time() - embed_start)*1000:.0f}ms")
        
        # Add to collection
        self.collection.add(
            ids=ids,
            embeddings=embeddings.tolist(),
            metadatas=metadatas,
            documents=documents
        )
        
        print(f"   ✅ Collection created with {len(faq_data)} items")
    
    def search(self, query: str, top_k: int = TOP_K) -> List[Dict]:
        """
        Search FAQ for relevant answers
        
        Args:
            query: User's question (can be Hindi or English)
            top_k: Number of results to return (default: 3)
        
        Returns:
            List of matching FAQ items with scores
        """
        start_time = time.time()
        
        # For e5 models, prefix query with "query: "
        query_text = f"query: {query}"
        
        # Generate query embedding
        query_embedding = self.model.encode([query_text])[0]
        
        # Search ChromaDB
        results = self.collection.query(
            query_embeddings=[query_embedding.tolist()],
            n_results=top_k,
            include=["metadatas", "distances"]
        )
        
        search_time = (time.time() - start_time) * 1000
        
        # Format results
        formatted_results = []
        if results['metadatas'] and results['metadatas'][0]:
            for i, metadata in enumerate(results['metadatas'][0]):
                distance = results['distances'][0][i] if results['distances'] else 0
                # Convert cosine distance to similarity score (1 - distance)
                similarity = 1 - distance
                
                formatted_results.append({
                    "question": metadata['question'],
                    "answer": metadata['answer'],
                    "category": metadata.get('category', 'general'),
                    "similarity": round(similarity, 3)
                })
        
        print(f"🔍 RAG search: {search_time:.0f}ms | Results: {len(formatted_results)} | Query: {query[:50]}...")
        
        return formatted_results
    
    def get_context(self, query: str, top_k: int = TOP_K) -> str:
        """
        Get formatted context string for LLM prompt
        
        Args:
            query: User's question
            top_k: Number of results to include
        
        Returns:
            Formatted context string for LLM
        """
        results = self.search(query, top_k)
        
        if not results:
            return ""
        
        context_parts = []
        for i, result in enumerate(results, 1):
            context_parts.append(
                f"[{i}] Q: {result['question']}\n"
                f"    A: {result['answer']}"
            )
        
        return "\n\n".join(context_parts)
    
    def rebuild_index(self) -> dict:
        """
        Force rebuild the FAQ index.
        Called when FAQs are updated via admin panel.
        
        Returns:
            Status dict with success flag and message
        """
        try:
            print(f"\n{'='*50}")
            print("🔄 Rebuilding RAG Index (Admin triggered)")
            print(f"{'='*50}")
            
            start_time = time.time()
            
            # Load fresh FAQ data
            faq_data = self._load_faq()
            
            # Delete old collection
            try:
                self.chroma_client.delete_collection(COLLECTION_NAME)
                print("   Deleted old collection")
            except Exception:
                pass
            
            # Create new collection with fresh data
            self._create_collection(faq_data)
            
            # Update stored hash
            current_hash = self._get_faq_hash()
            self._save_hash(current_hash)
            
            total_time = (time.time() - start_time) * 1000
            print(f"\n✅ RAG Index rebuilt in {total_time:.0f}ms")
            print(f"   Total FAQs: {len(faq_data)}")
            print(f"{'='*50}\n")
            
            return {
                "success": True,
                "message": f"Index rebuilt successfully with {len(faq_data)} FAQs",
                "time_ms": round(total_time),
                "faq_count": len(faq_data)
            }
            
        except Exception as e:
            print(f"❌ Error rebuilding index: {e}")
            return {
                "success": False,
                "message": f"Failed to rebuild index: {str(e)}"
            }


# Singleton instance
_rag_engine: Optional[RAGEngine] = None


def get_rag_engine() -> RAGEngine:
    """Get or create RAG engine instance"""
    global _rag_engine
    if _rag_engine is None:
        _rag_engine = RAGEngine()
    return _rag_engine


def search_faq(query: str, top_k: int = TOP_K) -> List[Dict]:
    """Convenience function to search FAQ"""
    return get_rag_engine().search(query, top_k)


def get_faq_context(query: str, top_k: int = TOP_K) -> str:
    """Convenience function to get formatted context"""
    return get_rag_engine().get_context(query, top_k)


# Test function
if __name__ == "__main__":
    print("\n" + "="*60)
    print("Testing RAG Engine")
    print("="*60 + "\n")
    
    # Initialize
    rag = get_rag_engine()
    
    # Test queries
    test_queries = [
        "B.Tech CSE की फीस कितनी है?",  # Hindi
        "What is the fee for B.Tech?",  # English
        "hostel में कितने तरह के rooms हैं?",  # Hindi
        "Which companies come for placement?",  # English
        "भीमताल कैंपस में कौन से courses हैं?",  # Hindi
        "How to apply for admission?",  # English
        "scholarship कैसे मिलती है?",  # Hindi
    ]
    
    print("\n" + "-"*60)
    for query in test_queries:
        print(f"\n📝 Query: {query}")
        results = rag.search(query)
        for i, r in enumerate(results, 1):
            print(f"   [{i}] ({r['similarity']:.2f}) {r['question'][:60]}...")
        print("-"*60)
