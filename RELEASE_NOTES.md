# SmartTranscriber v1.2.0

မြန်မာတရား MP3 အရှည်များအတွက် အသံအပိုင်းတစ်ပိုင်းချင်း သိမ်းပြီး ဆက်လုပ်နိုင်သော စနစ်ကို ပြင်ထားပါသည်။ ကွန်ရက်နှင့် Gemini quota ပြဿနာဖြစ်လျှင် အချိန်ကန့်သတ်၍ ပြန်ကြိုးစားပြီး၊ မပြီးသေးသောအပိုင်းမှ ဆက်လုပ်နိုင်ပါသည်။ ပြီးသလောက်စာသားနှင့် လုပ်ဆောင်နေသောအခြေအနေကို App ထဲတွင် ကြည့်နိုင်ပါသည်။

App ထဲမှ ဗားရှင်းအသစ်ကို စစ်ဆေး၊ ဒေါင်းလုဒ်နှင့် တင်နိုင်ပါသည်။ APK အရွယ်အစား၊ checksum ရှိလျှင် checksum၊ App အမည်၊ ဗားရှင်းကုဒ်နှင့် လက်မှတ်ကို စစ်ဆေးပါသည်။ Android install permission ပေးပြီး ပြန်လာလျှင် install ကို ဆက်လုပ်နိုင်ပါသည်။

Validation: final APK build and lint passed; all 11 JVM tests and all 4 Android instrumentation tests passed. The Android suite decoded a complete 65-minute stereo MP3 through MediaCodec and Sonic, verified contiguous chunk boundaries and duration, recovered an interrupted checkpoint, preserved a legacy transcript across the v1.1.0-to-v1.2.0 install, and replaced old scheduled delays once while retaining partial progress. The APK has the same signing certificate as v1.1.0. A live short spoken-audio API check completed using a fallback model after a 503 response. Burmese/Pali accuracy on the user’s actual sermon has not been tested.
