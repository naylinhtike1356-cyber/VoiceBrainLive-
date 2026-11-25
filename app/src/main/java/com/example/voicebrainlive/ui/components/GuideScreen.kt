package com.example.voicebrainlive.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class GuideSection(
    val title: String,
    val items: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onBack: () -> Unit) {
    val sections = listOf(
        GuideSection(
            title = "၁။ အသံဖြင့် နေ့စဉ်ခိုင်းစေနိုင်သော Commands များ",
            items = listOf(
                "• ဖုန်းခေါ်ရန် — \"မေမေ့ကို ဖုန်းခေါ်ပေး\" / \"Call Mom\"",
                "• သတိပေးချက် & Alarm — \"မနက် ၇ နာရီ Alarm ပေး\" / \"နောက် ၁၀ မိနစ်နေရင် သတိပေး\"",
                "• ပြက္ခဒိန် & ရာသီဥတု — \"ဒီနေ့ အစီအစဉ်\" / \"ဒီနေ့ မိုးရွာမလား\"",
                "• သီချင်း & ဖုန်းထိန်းချုပ်မှု — \"Spotify ဖွင့်\" / \"အသံတိုး\" / \"မီးအိမ်ဖွင့်\""
            )
        ),
        GuideSection(
            title = "၂။ Modes & Routines (အလိုအလျောက် စနစ်)",
            items = listOf(
                "• Work Mode (\"အလုပ်ဝင်တော့မယ်\") — ဖုန်းသံတိတ်/တုန်ခါပြောင်းခြင်း၊ Volume ချိန်ခြင်း၊ သီချင်းဖွင့်ခြင်း။",
                "• Bedtime Mode — Sleep Timer ဖြင့် သီချင်းအလိုအလျောက် ပိတ်ပေးခြင်း။",
                "• Morning Brief — မနက်တိုင်း ရာသီဥတုနှင့် အစီအစဉ်များကို အသံဖြင့် သတိပေးခြင်း။"
            )
        ),
        GuideSection(
            title = "၃။ ကိုယ်ရေးကိုယ်တာ မှတ်ဉာဏ် & Backup",
            items = listOf(
                "• \"မှတ်ထားပေး\" ဟု ပြော၍ အရေးကြီးအချက်များကို သိမ်းဆည်းနိုင်သည်။",
                "• Memory Manager > Top Menu မှတစ်ဆင့် JSON Backup ထုတ်ယူခြင်း (Export) နှင့် ပြန်သွင်းခြင်း (Import) ပြုလုပ်နိုင်သည်။"
            )
        ),
        GuideSection(
            title = "၄။ လုံခြုံရေး နှင့် App Update",
            items = listOf(
                "• ဖုန်းခေါ်ခြင်း၊ SMS/Messenger စာပို့ခြင်းကဲ့သို့ အရေးကြီးလုပ်ဆောင်ချက်များကို အသုံးပြုသူ အတည်ပြုချက်မပါဘဲ အလိုအလျောက် မလုပ်ဆောင်ပါ။",
                "• Settings > App updates မှတစ်ဆင့် ဗားရှင်းအသစ်များကို တိုက်ရိုက် Update တင်နိုင်ပါသည်။"
            )
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("အသုံးပြုနည်း လမ်းညွှန်") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(sections) { section ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = section.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        section.items.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
