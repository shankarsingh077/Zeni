"""
Zeni Action Engine
AI-driven action system - LLM reasons and decides what actions to take.

No intent matching, no keywords - pure reasoning.
Flow: User Speech → LLM Reasoning → Action Decision → Execute
"""

import json
import os
from typing import Optional, Dict, List, Any
from dataclasses import dataclass

from core.logging import get_logger

logger = get_logger("actions")

# Path to data files
DATA_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "data")
TOURS_PATH = os.path.join(DATA_DIR, "tours.json")
FEES_PATH = os.path.join(DATA_DIR, "fees.json")


@dataclass
class ActionResult:
    """Result of action execution"""
    action_type: str  # "campus_tour", "none", etc.
    data: Optional[Dict[str, Any]] = None  # Action-specific data


class ActionEngine:
    """
    Manages available actions and provides context for LLM reasoning.
    
    The LLM decides what action to take - this engine just:
    1. Provides available actions to the LLM
    2. Executes the action the LLM decides on
    3. Returns results
    """
    
    def __init__(self):
        self.tours: Dict[str, Dict] = {}
        self.fees: Dict[str, Dict] = {}
        self._load_tours()
        self._load_fees()
    
    def _load_tours(self):
        """Load available campus tours"""
        try:
            with open(TOURS_PATH, 'r', encoding='utf-8') as f:
                data = json.load(f)
                # Index by ID for quick lookup
                for tour in data.get('tours', []):
                    self.tours[tour['id']] = tour
                logger.info("tours_loaded", count=len(self.tours))
        except Exception as e:
            logger.error("tours_load_failed", error=str(e))
    
    def _load_fees(self):
        """Load available fee structures"""
        try:
            with open(FEES_PATH, 'r', encoding='utf-8') as f:
                data = json.load(f)
                # Index by ID for quick lookup
                for program in data.get('programs', []):
                    self.fees[program['id']] = program
                logger.info("fees_loaded", count=len(self.fees))
        except Exception as e:
            logger.error("fees_load_failed", error=str(e))
    
    def get_available_actions_prompt(self) -> str:
        """
        Generate the actions/tools description for the LLM system prompt.
        This tells the LLM what actions it can take.
        """
        # List tour locations for the LLM
        tour_list = ", ".join([
            f"{t['name']} ({t['id']})" 
            for t in self.tours.values()
        ])
        
        # List fee programs for the LLM
        fee_list = ", ".join([
            f"{p['name']} ({p['id']})" 
            for p in self.fees.values()
        ])
        
        return f"""
AVAILABLE ACTIONS:
You can trigger actions by including a JSON block in your response. Only use actions when clearly appropriate.

1. CAMPUS_TOUR - Open a 360° virtual tour of campus facilities
   Available locations: {tour_list}
   
   When user wants to see/visit/explore any campus location, include this in your response:
   ```action
   {{"action": "campus_tour", "tour_id": "<location_id>"}}
   ```
   
   Examples of when to use:
   - "Show me the library" → Use campus_tour with tour_id "library"
   - "I want to see the hostel" → Use campus_tour with tour_id "boys_hostel" or "girls_hostel"
   - "Can I see the gym?" → Use campus_tour with tour_id "gymnasium"
   - "Take me to seminar hall" → Use campus_tour with tour_id "seminar_hall"
   - "दिखाओ कंप्यूटर लैब" → Use campus_tour with tour_id "computer_lab"

   DO NOT use if user is just asking ABOUT a facility (fees, timings, etc.) - only when they want to SEE it.

2. FEE_STRUCTURE - Show detailed fee structure for a program
   Available programs: {fee_list}
   
   When user wants to SEE or VIEW the DETAILED fee structure (not just hear about it), include this:
   ```action
   {{"action": "fee_structure", "program_id": "<program_id>"}}
   ```
   
   Examples of when to use:
   - "Show me the detailed fees for B.Tech CSE" → Use fee_structure with program_id "btech-cse"
   - "I want to see the complete fee structure for MBA" → Use fee_structure with program_id "mba"
   - "Can you display the BCA fees?" → Use fee_structure with program_id "bca"
   - "Show detailed fee breakdown for nursing" → Use fee_structure with program_id "bsc-nursing"
   - "मुझे BBA की फीस दिखाओ" → Use fee_structure with program_id "bba"
   
   IMPORTANT: Only use this when user explicitly asks to SEE/VIEW/DISPLAY the detailed fees.
   If user just asks "What is the fee?" or "kitni fees hai?" - just tell them verbally, don't show the page.

3. SHOW_PLACEMENTS - Show top placement students photo gallery
   
   When user wants to SEE the top placed students or placement records, include this:
   ```action
   {{"action": "show_placements"}}
   ```
   
   Examples of when to use:
   - "Show me the top placed students" → Use show_placements
   - "I want to see placement records" → Use show_placements
   - "Can you show placements?" → Use show_placements
   - "Show top placements" → Use show_placements
   - "मुझे प्लेसमेंट दिखाओ" → Use show_placements
   - "Are there any placed students? Show me" → Use show_placements
   
   IMPORTANT: Only use this when user explicitly asks to SEE/VIEW/SHOW the placements.
   If user just asks "How are placements?" or "placement kaise hai?" - just tell them verbally about placement statistics.

RESPONSE FORMAT:
- Always respond naturally to the user
- If an action is needed, include the action block at the END of your response
- Example response with action:
  "Sure! Let me show you our library. It has over 50,000 books and digital resources.
  ```action
  {{"action": "campus_tour", "tour_id": "library"}}
  ```"
  
- Example with fee structure:
  "Here's the detailed fee structure for B.Tech CSE. Let me display it for you.
  ```action
  {{"action": "fee_structure", "program_id": "btech-cse"}}
  ```"

- Example with placements:
  "Here are our top placed students! We have excellent placement records.
  ```action
  {{"action": "show_placements"}}
  ```"
"""
    
    def get_tour_ids(self) -> List[str]:
        """Get list of valid tour IDs"""
        return list(self.tours.keys())
    
    def get_fee_program_ids(self) -> List[str]:
        """Get list of valid fee program IDs"""
        return list(self.fees.keys())
    
    def execute_action(self, action_type: str, action_data: Dict) -> Optional[ActionResult]:
        """
        Execute an action decided by the LLM.
        
        Args:
            action_type: Type of action ("campus_tour", "fee_structure", etc.)
            action_data: Action parameters from LLM
            
        Returns:
            ActionResult with execution data, or None if invalid
        """
        if action_type == "campus_tour":
            tour_id = action_data.get("tour_id", "")
            
            # Find the tour
            tour = self.tours.get(tour_id)
            if not tour:
                # Try fuzzy match on name
                tour_id_lower = tour_id.lower().replace(" ", "_")
                for tid, t in self.tours.items():
                    if tid == tour_id_lower or t['name'].lower() == tour_id.lower():
                        tour = t
                        break
            
            if tour:
                logger.info("action_executed", 
                           action="campus_tour", 
                           tour_id=tour['id'],
                           tour_name=tour['name'])
                return ActionResult(
                    action_type="campus_tour",
                    data={
                        "tour_id": tour['id'],
                        "name": tour['name'],
                        "url": tour['url'],
                        "description": tour['description']
                    }
                )
            else:
                logger.warning("tour_not_found", tour_id=tour_id)
                return None
        
        elif action_type == "fee_structure":
            program_id = action_data.get("program_id", "")
            
            # Find the program
            program = self.fees.get(program_id)
            if not program:
                # Try fuzzy match on name or aliases
                program_id_lower = program_id.lower().replace(" ", "-")
                for pid, p in self.fees.items():
                    if pid == program_id_lower or p['name'].lower() == program_id.lower():
                        program = p
                        break
                    # Check aliases
                    for alias in p.get('aliases', []):
                        if alias.lower() == program_id.lower():
                            program = p
                            break
                    if program:
                        break
            
            if program:
                logger.info("action_executed", 
                           action="fee_structure", 
                           program_id=program['id'],
                           program_name=program['name'])
                return ActionResult(
                    action_type="fee_structure",
                    data={
                        "program_id": program['id'],
                        "program_name": program['name'],
                        "url": program['url']
                    }
                )
            else:
                logger.warning("fee_program_not_found", program_id=program_id)
                return None
        
        elif action_type == "show_placements":
            # Show top placements - no parameters needed
            logger.info("action_executed", action="show_placements")
            return ActionResult(
                action_type="show_placements",
                data={
                    "title": "Top Placements - GEHU Bhimtal"
                }
            )
        
        logger.warning("unknown_action", action_type=action_type)
        return None


def parse_action_from_response(response_text: str) -> tuple[str, Optional[Dict]]:
    """
    Parse action block from LLM response.
    
    Returns:
        Tuple of (clean_text, action_dict or None)
    """
    import re
    
    # Look for action block
    action_pattern = r'```action\s*\n?\s*(\{[^}]+\})\s*\n?```'
    match = re.search(action_pattern, response_text, re.DOTALL)
    
    if match:
        try:
            action_json = match.group(1)
            action_data = json.loads(action_json)
            
            # Remove action block from response text
            clean_text = re.sub(action_pattern, '', response_text).strip()
            
            return clean_text, action_data
        except json.JSONDecodeError as e:
            logger.warning("action_json_parse_failed", error=str(e))
    
    return response_text, None


# Singleton instance
_action_engine: Optional[ActionEngine] = None


def get_action_engine() -> ActionEngine:
    """Get or create action engine instance"""
    global _action_engine
    if _action_engine is None:
        _action_engine = ActionEngine()
    return _action_engine
