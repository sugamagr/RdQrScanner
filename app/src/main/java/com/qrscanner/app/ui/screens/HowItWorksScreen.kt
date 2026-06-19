package com.qrscanner.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qrscanner.app.ui.theme.AccentCoral
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.PrimaryOrangeLight
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.WarningAmber
import kotlinx.coroutines.launch

@Composable
fun HowItWorksScreen(
    onNavigateBack: () -> Unit
) {
    // 0 = Hindi (default), 1 = English
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFFFF8F0), Color.White, Color(0xFFFFF8F0))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Gray.copy(alpha = 0.1f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                
                Text(
                    text = if (pagerState.currentPage == 0) "यह कैसे काम करता है" else "How It Works",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
                
                Spacer(modifier = Modifier.width(48.dp))
            }
            
            // Language Tabs
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp)),
                containerColor = Color.White,
                contentColor = PrimaryOrange,
                indicator = { tabPositions ->
                    SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                        height = 3.dp,
                        color = PrimaryOrange
                    )
                }
            ) {
                val hindiColor by animateColorAsState(
                    targetValue = if (pagerState.currentPage == 0) PrimaryOrange else TextSecondary,
                    animationSpec = spring(stiffness = Spring.StiffnessLow),
                    label = "hindiColor"
                )
                val englishColor by animateColorAsState(
                    targetValue = if (pagerState.currentPage == 1) PrimaryOrange else TextSecondary,
                    animationSpec = spring(stiffness = Spring.StiffnessLow),
                    label = "englishColor"
                )
                
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { 
                        scope.launch { 
                            pagerState.animateScrollToPage(0) 
                        } 
                    },
                    text = { 
                        Text(
                            "हिंदी",
                            fontWeight = if (pagerState.currentPage == 0) FontWeight.Bold else FontWeight.Normal,
                            color = hindiColor
                        ) 
                    }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { 
                        scope.launch { 
                            pagerState.animateScrollToPage(1) 
                        } 
                    },
                    text = { 
                        Text(
                            "English",
                            fontWeight = if (pagerState.currentPage == 1) FontWeight.Bold else FontWeight.Normal,
                            color = englishColor
                        ) 
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Swipeable Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> HindiContent()
                    1 -> EnglishContent()
                }
            }
        }
    }
}

@Composable
private fun HindiContent() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(listOf(PrimaryOrange, PrimaryOrangeLight))
                        )
                        .padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "RD बुक स्कैनर",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "RD खाता QR कोड को स्कैन और\nप्रबंधित करने का संपूर्ण समाधान",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        
        // Key Concepts
        item {
            SectionHeader(text = "मुख्य अवधारणाएं")
        }
        
        item {
            ConceptCard(
                icon = Icons.Default.Numbers,
                title = "RD खाता संख्या",
                description = "9-15 अंकों की एक संख्या जो RD (आवर्ती जमा) बुक की पहचान करती है। प्रत्येक RD बुक पर QR कोड में यह नंबर होता है।",
                color = PrimaryOrange
            )
        }
        
        item {
            ConceptCard(
                icon = Icons.Default.FolderOpen,
                title = "LOT (लॉट)",
                description = "एक साथ स्कैन किए गए RD नंबरों का समूह। इसे एक बैच समझें। आप एक LOT में कई नंबर स्कैन कर सकते हैं, फिर अगले बैच के लिए नया LOT शुरू कर सकते हैं।",
                color = AccentMint
            )
        }
        
        item {
            ConceptCard(
                icon = Icons.Default.CheckCircle,
                title = "सेशन",
                description = "कई LOTs वाला एक पूर्ण स्कैनिंग सत्र। जब आप दिन की स्कैनिंग समाप्त करें, तो सभी LOTs को एक साथ सेव करने के लिए सेशन समाप्त करें।",
                color = AccentCoral
            )
        }
        
        // How to Use
        item {
            SectionHeader(text = "उपयोग कैसे करें")
        }
        
        item {
            StepCard(
                stepNumber = 1,
                title = "स्कैनिंग शुरू करें",
                description = "होम स्क्रीन पर 'Scan RD Books' पर टैप करें। एक नया सेशन अपने आप शुरू हो जाएगा।"
            )
        }
        
        item {
            StepCard(
                stepNumber = 2,
                title = "QR कोड स्कैन करें",
                description = "अपना कैमरा RD बुक के QR कोड पर ले जाएं। वैध नंबर (9-15 अंक) वर्तमान LOT में जुड़ जाएंगे। डुप्लिकेट अपने आप पहचान लिए जाते हैं।"
            )
        }
        
        item {
            StepCard(
                stepNumber = 3,
                title = "LOT समाप्त करें",
                description = "जब एक बैच पूरा हो जाए, तो 'Finish LOT' पर टैप करें। फिर आप अगला LOT स्कैन कर सकते हैं या सेशन समाप्त कर सकते हैं।"
            )
        }
        
        item {
            StepCard(
                stepNumber = 4,
                title = "सेशन समाप्त करें",
                description = "'End' पर टैप करके अपना सेशन पूरा करें और सेव करें। आप सेशन विवरण में जाएंगे जहां डेटा शेयर या एक्सपोर्ट कर सकते हैं।"
            )
        }
        
        // Features
        item {
            SectionHeader(text = "विशेषताएं")
        }
        
        item {
            FeatureItem(
                icon = Icons.Default.Share,
                title = "LOT शेयर करें",
                description = "LOT नंबर दिखाने वाली इमेज के साथ अलग-अलग LOT शेयर करें। नंबर कॉमा से अलग होते हैं जिससे कॉपी-पेस्ट आसान होता है।"
            )
        }
        
        item {
            FeatureItem(
                icon = Icons.Default.PictureAsPdf,
                title = "QR PDF बनाएं",
                description = "RD नंबरों के लिए पासपोर्ट साइज QR कोड बनाएं। नंबर मैन्युअल डालें और प्रिंट करने योग्य PDF बनाएं।"
            )
        }
        
        item {
            FeatureItem(
                icon = Icons.Default.EditCalendar,
                title = "डिफॉल्टर मार्क करें",
                description = "एक LOT स्कैन करने के बाद, उन खातों को मार्क करें जिनके लिए एक से अधिक महीने का भुगतान हुआ है। हर डिफॉल्टर के लिए कौन से महीने (जैसे जून, जुलाई 2024) हैं, यह भी चुनें — पोर्टल से मिलान करने में आसानी होगी। महीने LOT कार्ड और सभी एक्सपोर्ट में दिखेंगे।"
            )
        }

        item {
            FeatureItem(
                icon = Icons.Default.FolderOpen,
                title = "सेशन एक्सपोर्ट करें",
                description = "रिकॉर्ड रखने या आगे की प्रक्रिया के लिए पूरे सेशन को XLSX या TXT फाइल के रूप में एक्सपोर्ट करें।"
            )
        }
        
        item {
            Spacer(modifier = Modifier.height(16.dp).navigationBarsPadding())
        }
    }
}

@Composable
private fun EnglishContent() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(listOf(PrimaryOrange, PrimaryOrangeLight))
                        )
                        .padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "RD Book Scanner",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Your complete solution for managing\nRD Account QR codes",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        
        // Key Concepts
        item {
            SectionHeader(text = "Key Concepts")
        }
        
        item {
            ConceptCard(
                icon = Icons.Default.Numbers,
                title = "RD Account Number",
                description = "A 9-15 digit number that uniquely identifies an RD (Recurring Deposit) book. Each QR code on an RD book contains this number.",
                color = PrimaryOrange
            )
        }
        
        item {
            ConceptCard(
                icon = Icons.Default.FolderOpen,
                title = "LOT",
                description = "A group of RD numbers scanned together. Think of it as a batch. You can scan multiple numbers into one LOT, then start a new LOT for the next batch.",
                color = AccentMint
            )
        }
        
        item {
            ConceptCard(
                icon = Icons.Default.CheckCircle,
                title = "Session",
                description = "A complete scanning session containing multiple LOTs. When you finish scanning for the day, end the session to save all your LOTs together.",
                color = AccentCoral
            )
        }
        
        // How to Use
        item {
            SectionHeader(text = "Step by Step")
        }
        
        item {
            StepCard(
                stepNumber = 1,
                title = "Start Scanning",
                description = "Tap 'Scan RD Books' on the home screen. A new session starts automatically."
            )
        }
        
        item {
            StepCard(
                stepNumber = 2,
                title = "Scan QR Codes",
                description = "Point your camera at RD book QR codes. Valid numbers (9-15 digits) are added to the current LOT. Duplicates are automatically detected."
            )
        }
        
        item {
            StepCard(
                stepNumber = 3,
                title = "Finish LOT",
                description = "When done with a batch, tap 'Finish LOT' to save it. You can then continue scanning the next LOT or end the session."
            )
        }
        
        item {
            StepCard(
                stepNumber = 4,
                title = "End Session",
                description = "Tap 'End' to complete and save your session. You'll be taken to the session details where you can share or export the data."
            )
        }
        
        // Features
        item {
            SectionHeader(text = "Features")
        }
        
        item {
            FeatureItem(
                icon = Icons.Default.Share,
                title = "Share LOTs",
                description = "Share individual LOTs with an image showing the LOT number. Numbers are comma-separated for easy copy-paste."
            )
        }
        
        item {
            FeatureItem(
                icon = Icons.Default.PictureAsPdf,
                title = "Generate QR PDF",
                description = "Create passport-size QR codes for RD numbers. Enter numbers manually and generate a printable PDF."
            )
        }
        
        item {
            FeatureItem(
                icon = Icons.Default.EditCalendar,
                title = "Mark Defaulters",
                description = "After scanning a LOT, mark any account that paid for more than one month — and pick which months (e.g. Jun, Jul 2024) so you can reconcile against the postal portal. Months show up on the LOT card and in every export."
            )
        }

        item {
            FeatureItem(
                icon = Icons.Default.FolderOpen,
                title = "Export Sessions",
                description = "Export complete sessions as XLSX or TXT files for record-keeping or further processing."
            )
        }
        
        item {
            Spacer(modifier = Modifier.height(16.dp).navigationBarsPadding())
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Bold
        ),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun ConceptCard(
    icon: ImageVector,
    title: String,
    description: String,
    color: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(26.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(14.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.2
                )
            }
        }
    }
}

@Composable
private fun StepCard(
    stepNumber: Int,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(PrimaryOrange, PrimaryOrangeLight))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stepNumber.toString(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
            }
            
            Spacer(modifier = Modifier.width(14.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.2
                )
            }
        }
    }
}

@Composable
private fun FeatureItem(
    icon: ImageVector,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = WarningAmber,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(14.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}
