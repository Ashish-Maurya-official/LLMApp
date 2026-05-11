package com.example.llmapp.core.governance

enum class EpistemicState(val weight: Int) {
    /** 
     * Explicitly stated by the user (e.g., "I am allergic to peanuts"). 
     * Highest inertia. Cannot be overwritten by inferences. 
     */
    VERIFIED(100),
    
    /** 
     * High-confidence deduction from multiple assumed sources or explicit partial facts. 
     */
    PROBABLE(75),
    
    /** 
     * Single-shot inference by the LLM (e.g. guessing user likes coffee because they mentioned a cafe). 
     * Low inertia. Easily overwritten. 
     */
    ASSUMED(25),
    
    /** 
     * A memory that was previously assumed but later proven false by a VERIFIED fact. 
     * Kept for historical context so the LLM doesn't make the same mistake twice.
     */
    CONTRADICTED(0)
}
