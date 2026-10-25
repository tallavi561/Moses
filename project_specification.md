# מסמך אפיון טכני: מערכת ניטור לוגים עצמאית (Custom Log Monitor)

## 1. מטרת הפרויקט
בניית כלי ניטור לוגים חינמי, פשוט ויעיל המבוסס על עקרונות של מנועי חיפוש (Inverted Index), במטרה לספק אלטרנטיבה קלה ומותאמת אישית לכלים יקרים כמו Splunk. המערכת תוכננה לרוץ על גבי **MongoDB** ותתמקד בביצועים גבוהים ובניהול זיכרון ואחסון חכמים.

---

## 2. ארכיטקטורה ועיצוב נתונים (MongoDB)
המערכת מבוססת על הפרדה ברורה בין המידע הגולמי לבין אינדקס החיפוש, תוך שימוש בשני אוספים (Collections) מרכזיים:

### א. אוסף הלוגים (Log Store Collection)
שומר את המידע הגולמי המלא ואת המטא-דאטה הנלווה לכל הודעת לוג.
* **מבנה מסמך לדוגמה:**
  ```json
  {
    "log_id": "01H... (ULID / ObjectId)",
    "timestamp": "2026-09-11T12:05:30.000Z",
    "service_name": "auth-service",
    "log_level": 2, 
    "ip": "192.168.1.10",
    "raw_message": "User failed login while validating token"
  }
  ```
* *הערה:* רמת הלוג (INFO, ERROR וכו') נשמרת כ-Enum מספרי (למשל: 1, 2) לצורך סינון מהיר מבלי לזהם את אינדקס המילים.

### ב. אוסף אינדקס הפוך מבוסס זמן (Time-Bucketed Inverted Index Collection)
מנוע החיפוש של המערכת. במקום לשמור מערך ענק ואינסופי של מזהי לוגים תחת כל מילה, האינדקס מפוצל לחלונות זמן (Buckets), כך שנוצר מסמך נפרד לכל שילוב של מילה וחלון זמן.
* **מבנה מסמך לדוגמה:**
  ```json
  {
    "word": "WHILE",
    "bucket": "2026-09-11_12:00",
    "log_ids": ["L1", "L5", "L102"]
  }
  ```
* **יתרונות המבנה:**
  1. **גודל מסמך חסום (Bounded Size):** מניעת התנפחות מסמכים שתגרום לחריגה ממגבלת ה-16MB של MongoDB.
  2. **מחיקה יעילה (Retention):** אפשרות למחוק לוגים ישנים על ידי מחיקת ה-Buckets הישנים בשלמותם (במקום לבצע עדכונים כבדים בתוך מערכים עצומים), ואף שימוש באינדקס TTL של MongoDB.

---

## 3. זרימת כתיבה (Ingestion Flow)
כאשר לוג חדש נכנס למערכת:
1. **הקצאת מזהה:** מופק מזהה ייחודי מבוסס-זמן (כמו `ObjectId` או `ULID`) המאפשר מיון כרונולוגי.
2. **שמירה גולמית:** הלוג נשמר בשלמותו ב-`Log Store Collection`.
3. **פירוק למילים (Tokenization):** הודעת הלוג מפורקת למילים (נכון לעכשיו, מילים מלאות כולל ביטויים כמו `java.lang.NullPointerException` כמילה אחת).
4. **עדכון האינדקס:** עבור כל מילה, המערכת מעדכנת (או יוצרת) את המסמך המתאים ל-Bucket הנוכחי ומוסיפה את ה-`log_id` למערך ה-`log_ids`.

---

## 4. זרימת חיפוש (Search Flow)
כאשר משתמש מריץ שאילתה (למשל: מילים X ו-Y, בטווח זמנים מסוים, וברמת לוג ERROR):
1. **סינון גס (Macro-Filter):** 
   * המערכת בוחרת את ה-Buckets הרלוונטיים לטווח הזמנים המבוקש.
   * שולפת את מערכי ה-`log_ids` עבור המילים X ו-Y מה-Buckets הללו.
2. **חיתוך (Intersection):** ביצוע פעולת AND בין מערכי ה-IDs של המילים המבוקשות.
3. **סינון עדין (Micro-Filter):** סינון רשימת ה-IDs לפי הטווח המדויק ברמת המילי-שנייה (באמצעות מיון המזהים או שליפה מול ה-Log Store).
4. **שליפת תוצאות:** שליפת המסמכים המלאים מתוך ה-`Log Store Collection` והצגתם למשתמש.

---

## 5. נושאים שהוחלטו ומומשו (Resolved Points)

1. **Tokenization:** פיצול לפי whitespace בלבד (שומר ביטויים כמו `java.lang.NullPointerException` כמילה אחת), הנמכת אותיות (lowercase) מלאה, **בלי** סינון Stop Words. מומש ב-`Tokenizer.java`.
2. **High Cardinality:** IP/UUID/ISO-timestamp/מספרים ארוכים מזוהים ב-Regex ומוחלפים ב-placeholder גנרי (`<ip>`, `<uuid>`, `<timestamp>`, `<num>`) *לפני* הטוקניזציה. הטקסט הגולמי המלא עדיין נשמר ב-Log Store ל-grep מדויק. מומש ב-`Tokenizer.java`.
3. **Batching:** In-memory buffer (`ConcurrentHashMap`, thread-safe) שצובר עדכוני אינדקס, עם flush מתוזמן (ברירת מחדל: 5 שניות, `moses.ingestion.flush-interval-seconds`) שמבצע Bulk Upsert (`$addToSet`, אידמפוטנטי) ל-MongoDB. מומש ב-`IngestionBuffer.java` + `IndexFlushScheduler.java`.
4. **Data Retention:** TTL index על `logs.timestamp` ועל `index_buckets.bucket_start`, ברירת מחדל 14 יום (`moses.retention.days`), נבנה/מתעדכן אוטומטית בעלייה של האפליקציה. מומש ב-`RetentionIndexInitializer.java`.

המימוש (Java 21 / Spring Boot / MongoDB) פוצל ל-6 מיקרו-סרביסים - ראו [ARCHITECTURE.md](ARCHITECTURE.md) לטופולוגיה המלאה ולכל ההחלטות. המקביליות ב-`ingestion-service` ממומשת עם Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`), וכל השירותים רצים עם `spring.threads.virtual.enabled: true`.