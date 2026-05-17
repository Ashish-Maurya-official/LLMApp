package com.example.llmapp.core.search.rules

import com.example.llmapp.core.search.SearchType
import com.example.llmapp.core.search.classification.SearchRule

/**
 * Isolated financial and stock queries classification rules.
 */
object FinanceRules {
    val rule = SearchRule(
        type = SearchType.FINANCE,
        keywords = setOf(
            "bitcoin", "crypto", "price of", "stock", "nasdaq", "dow jones",
            "market price", "live price", "exchange rate", "usd to", "ticker"
        ),
        confidence = 0.9f,
        description = "Matches live stock ticker changes, crypto values, and exchange rates."
    )
}
