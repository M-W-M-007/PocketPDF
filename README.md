# PocketPDF

Android PDF editor. Free, offline, koi subscription nahi, koi account nahi.

Build: Android Studio → **File → Open** → yeh folder → sync → **Build → Build Bundle(s)/APK(s) → Build APK(s)**.
APK: `app/build/outputs/apk/debug/app-debug.apk`

---

## Kya karta hai

**Text**
- **Edit** — kisi bhi line par tap karke usse badalna
- **Add text** — nayi jagah likhna; neeche ka purana text dhakne ka option saath hai
- **Erase** — box kheenchkar koi bhi hissa mitana
- **Sign** — ungli se signature banakar page par lagana

### Patch ki border nahi hoti

Har patch sirf `fill()` se banta hai, `stroke()` kahin use nahi hota — matlab
rectangle ke chaaron taraf koi outline nahi aati. Rang bhi page se hi sample
hota hai. Isliye patch page ka hissa lagta hai, chipkaya hua kaagaz nahi.

Agar aap purane text ke upar bina dhake likhoge toh dono overlap karke ganda
dikhega — isiliye **Add text** mein "neeche ka text pehle dhak dein" by default
ON hai.

**Pages**
- Rotate, delete, reorder, kuch pages hi rakhna (extract)
- Dusri PDFs ke saath merge
- 1 / 2 / 5 / 10 pages ke hisaab se split

**Convert**
- PDF → JPG images
- Images → PDF
- PDF → text file

---

## Text editing kaise kaam karta hai — aur iski seema

PDF paragraph store nahi karta. Woh har akshar ki position, font aur encoding
store karta hai, aur font aksar **subsetted** hota hai — matlab file mein sirf
wahi glyphs hain jo already use hue hain. Isliye content stream ke andar ghus kar
text replace karna unreliable hai: aap koi naya akshar type karo aur woh blank
aa jaaye.

Yeh app woh nahi karti. Iske bajaye:

1. Purane text ke upar ek rectangle lagti hai, jiska rang **page se hi sample**
   kiya jaata hai (isliye coloured paper par bhi patch nahi dikhta).
2. Uske upar naya text standard font (Helvetica) mein likha jaata hai.
3. Agar naya text lamba hai, font size apne aap chhota ho jaata hai taaki
   aas-paas ke text se na takraye.

### Kaunse documents par ye theek chalega — aur kaunse par nahi

**Theek chalega:** forms, letters, invoices, certificates, agreements — jahan
plain paragraphs hain aur aapko naam, date, amount ya ek line badalni hai.

**Mat use karo:** resume/CV, brochures, magazines, multi-column layouts.
Inme ek hi line ke andar bold aur normal mix hota hai, bullets hote hain, aur
line spacing bahut ghani hoti hai. Patch ek hi font aur ek hi weight mein likhta
hai, toh badla hua line aas-paas se alag dikhega. Resume Word ya Google Docs mein
edit karke PDF export karna hi sahi hai — ye app us kaam ke liye nahi hai.

**Iska matlab, imaandari se:**
- Naam, date, amount, typo — yeh bilkul theek kaam karega.
- Font exactly original jaisa nahi hoga. Serif document mein patch Helvetica mein
  dikhega.
- Poora paragraph dobara likhna, ya text ka reflow — yeh app nahi karti. Uske liye
  laptop par LibreOffice Draw behtar hai.
- Devanagari ya koi bhi non-Latin text standard font mein nahi likha ja sakta —
  woh characters drop ho jayenge.

## Scanned PDFs

Scan ki hui PDF mein text hota hi nahi, sirf tasveer hoti hai. Wahan **Edit** mode
kuch nahi dhoondh paayega — yeh app ki kami nahi, file mein text hai hi nahi.
Aise page par **Add text** use karein (upar se likh dein), ya pehle OCR karein.

---

## Aapki original file

Kabhi nahi badalti. Jo PDF aap kholte hain uski ek **working copy** cache mein
banti hai, saare edits usi par hote hain, aur **Save** hamesha ek nayi file
banata hai. Galat edit ho jaaye toh file dobara kholkar shuru kar sakte hain.

---

## Architecture

| Kaam | Library | Kyun |
|---|---|---|
| Rendering | Android `PdfRenderer` | Hardware-backed, PDFBox se kaafi tez |
| Writing | PDFBox-Android 2.0.27 | Page tree, content streams, fonts, images |

Rendering aur writing alag libraries se isliye hain ki dono apne-apne kaam mein
behtar hain.

## PDF → Word kyun nahi hai

Theek se karne ke liye layout, tables, styles sab dobara banana padta hai.
Aadha-adhoora version aisi .docx deta hai jo tooti hui dikhti hai aur jise theek
karne mein zyada time lagta hai. Iske bajaye **PDF → Text** hai — jo deta hai
wahi imaandari se deta hai, aur usko Word mein paste karna aasaan hai.
