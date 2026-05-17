package com.example.llmapp.ui.chat.composables.cards

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llmapp.ui.chat.state.WeatherDetails

@Composable
fun WeatherCard(
    details: WeatherDetails,
    modifier: Modifier = Modifier
) {
    val conditionLower = details.condition.lowercase()

    // Curated Harmonious Palette based on conditions
    val (backgroundColors, accentColor, weatherIcon) = remember(conditionLower) {
        when {
            conditionLower.contains("sun") || conditionLower.contains("clear") || conditionLower.contains("hot") -> {
                Triple(
                    listOf(Color(0xFFFF7E5F), Color(0xFFFEB47B)), // Warm Golden Orange Sunset
                    Color(0xFFFFD166),
                    Icons.Default.WbSunny
                )
            }
            conditionLower.contains("rain") || conditionLower.contains("storm") || conditionLower.contains("drizzle") || conditionLower.contains("shower") -> {
                Triple(
                    listOf(Color(0xFF2B5876), Color(0xFF4E4376)), // Deep Royal Indigo/Teal
                    Color(0xFF4EA8DE),
                    Icons.Default.Thunderstorm
                )
            }
            conditionLower.contains("snow") || conditionLower.contains("ice") || conditionLower.contains("freeze") || conditionLower.contains("cold") -> {
                Triple(
                    listOf(Color(0xFFE0F7FA), Color(0xFF80DEEA)), // Crispy Cyan Ice
                    Color(0xFF00E5FF),
                    Icons.Default.AcUnit
                )
            }
            else -> {
                Triple(
                    listOf(Color(0xFF37474F), Color(0xFF90A4AE)), // Elegant Slate Gray Cloud
                    Color(0xFFCFD8DC),
                    Icons.Default.Cloud
                )
            }
        }
    }

    val animatedBgStart by animateColorAsState(backgroundColors[0], animationSpec = tween(1000))
    val animatedBgEnd by animateColorAsState(backgroundColors[1], animationSpec = tween(1000))

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.25f),
                        Color.White.copy(alpha = 0.05f)
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            ),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(animatedBgStart.copy(alpha = 0.85f), animatedBgEnd.copy(alpha = 0.85f))
                    )
                )
                .padding(20.dp)
        ) {
            Column {
                // Header (Location and Weather Icon)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = details.location,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = details.condition,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = weatherIcon,
                            contentDescription = details.condition,
                            tint = accentColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Robustly clean and parse temperature and unit
                val tempClean = details.temp.replace("°", "").trim()
                val tempVal = tempClean.filter { it.isDigit() || it == '-' || it == '.' }
                val tempUnit = when {
                    details.temp.contains("F", ignoreCase = true) -> "°F"
                    details.temp.contains("C", ignoreCase = true) -> "°C"
                    else -> "°C" // default to Celsius
                }

                // Main Focus (Huge Temperature)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = tempVal.ifEmpty { "--" },
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Light,
                        color = Color.White,
                        lineHeight = 64.sp
                    )
                    Text(
                        text = tempUnit,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Light,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Secondary Stats Grid (Dynamic glass shelf details)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // High / Low stat
                        if (details.high != null || details.low != null) {
                            WeatherStatItem(
                                icon = Icons.Default.Thermostat,
                                label = "High/Low",
                                value = "${details.high ?: "--"} / ${details.low ?: "--"}"
                            )
                        }
                        
                        // Humidity stat
                        if (details.humidity != null) {
                            WeatherStatItem(
                                icon = Icons.Default.WaterDrop,
                                label = "Humidity",
                                value = details.humidity
                            )
                        }
                    }

                    if (details.wind != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            WeatherStatItem(
                                icon = Icons.Default.Air,
                                label = "Wind Speed",
                                value = details.wind
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherStatItem(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.5f)
            )
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}
