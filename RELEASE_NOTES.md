# SmartTranscriber v1.3.0

အသစ်ပြောင်းသော စာမူများတွင် ၁၀ မိနစ်ခြား timestamp ထည့်ထားပါသည်။ စာဖတ်မြင်ကွင်းတွင် အချိန်ခလုတ်ကို နှိပ်၍ သက်ဆိုင်ရာစာပိုဒ်သို့ တိုက်ရိုက်သွားနိုင်ပါသည်။ စာဖိုင်သိမ်းခြင်း၊ မျှဝေခြင်းနှင့် ကူးယူခြင်းတွင် ဖိုင်ခေါင်းစဉ်နှင့် အသံကြာချိန် ပါဝင်ပါသည်။

ပါဠိနှင့် မြန်မာစာလုံးပေါင်း၊ သဘာဝကျသော အဖြတ်အတောက်၊ အပိုဒ်ခွဲခြင်းနှင့် ဂါထာစာကြောင်းခွဲခြင်းအတွက် AI ညွှန်ကြားချက်များ တိုးမြှင့်ထားပါသည်။ မရှင်းလင်းသော စကားလုံးကို မှန်းမဖြည့်ရန် ညွှန်ကြားထားပါသည်။ မူလသိမ်းထားသော စာမူနှင့် ပြောင်းဆဲဖိုင်များ၏ checkpoint များကို ထိန်းသိမ်းထားပါသည်။

Gemini နှင့် Notion API key များကို ပုံမှန်ဖုံးထားပြီး မျက်လုံးခလုတ်ဖြင့် ဖွင့်/ပိတ်နိုင်ပါသည်။ စာဖတ်မြင်ကွင်း၏ အပိုဒ်အကွာအဝေးနှင့် စာကြောင်းအကွာအဝေးကို ဖတ်ရလွယ်အောင် ပြင်ထားပါသည်။

App ထဲရှိ Settings → App Update → စစ်ဆေးမည် မှ v1.3.0 ကို download လုပ်ပြီး install တင်နိုင်ပါသည်။ Android က တောင်းဆိုလျှင် SmartTranscriber အတွက် app install permission ပေးပါ။ App ကို uninstall လုပ်ရန် မလိုပါ။

Validation: all 16 JVM tests, debug APK build, Android-test APK compilation, and lint passed for the transcription and reader changes. Tests cover exact ten-minute PCM boundaries, sample continuity, deterministic checkpoint assembly, verse line breaks, legacy chunk layouts, and draft export headers. Android instrumentation tests were compiled but were not run for this release. Burmese/Pali accuracy on the user's actual sermon has not been measured.
