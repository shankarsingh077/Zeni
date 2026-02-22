import sys
import os
from pathlib import Path

# Add current directory to path so we can import core.config
sys.path.insert(0, os.getcwd()) # Use insert 0 to prioritize local

print(f"Current Working Directory: {os.getcwd()}")
print(f"sys.path: {sys.path}")

print("Attempting to import core.config...")
try:
    import core
    print(f"Imported 'core' from: {core.__file__}")
    
    from core import config as config_module
    print(f"Imported 'core.config' from: {config_module.__file__}")

    from core.config import config
    print(f"Successfully imported config.")
    print(f"Google Cloud Credentials Path: '{config.google_cloud.credentials_path}'")
        
except Exception as e:
    print(f"Error importing config: {e}")
    import traceback
    traceback.print_exc()
