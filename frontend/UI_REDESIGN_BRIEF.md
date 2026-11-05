# MoSes (Monitoring Services) — תיעוד UI מלא למטרת עיצוב מחדש

> מטרת המסמך: לתת למנוע/מעצב אחר (אדם או AI) את כל המידע הדרוש כדי **לעצב מחדש את ה-UI** של האפליקציה, בלי לגעת בלוגיקה/בקאנד. המסמך מתאר: מבנה כולל, כל מסך/דף, כל קומפוננטה, כל state/flow אינטראקטיבי, מודל הדאטה שמוצג, ומערכת העיצוב הנוכחית (טוקנים, צבעים, בעיות שגורמות למראה "AI generated").

---

## 1. מה האפליקציה בכלל

MoSes היא מערכת **מוניטורינג למיקרו-סרביסים**: מציגה לוגים, מצב בריאות (health) של שירותים, גרף/מפת תקשורת בין שירותים, וחיפוש לוגים (מובנה + שפה טבעית/AI). יש תמיכה דו-לשונית (עברית/אנגלית, RTL/LTR) ו-dark/light mode. Multi-tenant בצורה פשוטה: "פרויקטים" (כל פרויקט = מופע נפרד של המערכת שרואים לתוכו).

Stack: React + TypeScript + React Router + ReactFlow (לגרף) + CSS גלובלי אחד (`App.css` + `index.css`, אין Tailwind/design-system חוץ מ-CSS vars). אין ספריית קומפוננטות (לא MUI/shadcn/Chakra) — כל ה-UI נבנה מ-`<div className="...">` גנרי.

---

## 2. מבנה הניווט (Routing) — "מפת האתר"

```
/login                          → מסך התחברות (מדומה, לא אמיתי)
/projects                       → רשימת פרויקטים (landing אחרי login)
/p/:projectId/                  → redirect ל- /p/:projectId/search
/p/:projectId/search            → עמוד חיפוש לוגים
/p/:projectId/flow              → עמוד ארכיטקטורה/תקשורת בין שירותים (גרף)
/p/:projectId/dashboard          → "Coming soon" (לא מפותח, מופיע ב-sidebar אבל disabled)
/p/:projectId/uploads            → "Coming soon" (לא מפותח, disabled)
/p/:projectId/settings          → עמוד הגדרות (ערכת נושא + הורדת קובץ אינטגרציה)
* (כל נתיב לא מוכר)              → redirect ל- /projects
```

כל העמודים מתחת ל-`/p/:projectId` עטופים ב-**Layout** אחד קבוע: Sidebar + TopBar + תוכן העמוד (`<Outlet/>`).

**זרימת ניווט טיפוסית של משתמש:**
1. נכנס ל-`/login` → לוחץ "Sign in" (מדומה, אין שדות אמיתיים) → מועבר ל-`/projects`.
2. ב-`/projects` רואה רשימת פרויקטים קיימים + אפשרות ליצור פרויקט חדש (שם בלבד) → פותח פרויקט או יוצר אחד.
3. נכנס לתוך פרויקט (`/p/:id/search`) → רואה Sidebar קבוע עם 5 פריטי ניווט: Search, Flow (ארכיטקטורה), Dashboard (נעול), Uploads (נעול), Settings.
4. יכול להחליף פרויקט מתוך ה-Sidebar (ProjectSwitcher) בלי לחזור ל-`/projects`.
5. בתוך Flow יכול להוסיף/לערוך/למחוק שירותים וחיבורים בין שירותים — זה המסך האינטראקטיבי המורכב ביותר.

---

## 3. ה"שלד" הקבוע (Layout) — מופיע בכל עמוד בתוך פרויקט

### 3.1 Sidebar (סרגל ניווט צדי)
- שלוש מצבים: `expanded` (רחב, עם טקסט), `collapsed` (צר, רק אייקונים), `hidden` (מוסתר כליל — מוחלף בכפתור "☰" ב-TopBar).
- בראש ה-Sidebar: **ProjectSwitcher** — לוגו "MS" (ריבוע עם ראשי תיבות) + שם הפרויקט הנוכחי + טאגליין קבוע של האפליקציה + חץ (▾) שפותח dropdown.
  - ה-dropdown: רשימת שאר הפרויקטים (לחיצה = מעבר מיידי), קו מפריד, ואז "+ פרויקט חדש" שפותח input טקסט + כפתור יצירה inline (לא מודאל).
- כפתור Collapse/Expand (חץ) בראש ה-sidebar.
- תפריט ניווט: 5 פריטים (אייקון + טקסט), עם `NavLink` שמדגיש את הפריט הפעיל. פריטים disabled (Dashboard, Uploads) מקבלים נקודה קטנה "soon" ליד הטקסט (רק במצב expanded) אבל עדיין קליקביליים כ-route (מובילים ל-ComingSoonPage).
- כפתור "Hide" בתחתית ה-sidebar שמסתיר אותו לגמרי.

### 3.2 TopBar (סרגל עליון)
- שורה אחת, שני קצוות:
  - **שמאל**: אם ה-sidebar מוסתר — כפתור המבורגר (☰) להציג אותו בחזרה. אחרת ריק.
  - **ימין**: כפתור החלפת שפה (אייקון גלובוס + "English"/"עברית"), ואז **UserMenu**.
- **UserMenu**: עיגול עם ראשי תיבות המשתמש (אווטר טקסטואלי, לא תמונה). לחיצה פותחת dropdown: שם מלא + אימייל, קו מפריד, כפתור "Sign out".

### 3.3 אזור תוכן (`app-main`)
כל עמוד מרונדר כאן. אין breadcrumbs, אין page title גלובלי קבוע מעל התוכן — כל עמוד מציג כותרות (`<h2>`) בתוך ה-cards שלו.

---

## 4. מלאי "אבני בניין" (קומפוננטות UI גנריות שחוזרות על עצמן בכל האפליקציה)

חשוב למעצב: **אין ספריית עיצוב** — כל הדפים בנויים מהבלוקים האלה, בחזרתיות רבה. זו בעצם "המילון העיצובי" הקיים שצריך להמציא לו מחדש:

1. **`.card`** — קופסה לבנה/כהה עם רקע surface, border, radius, shadow. זו היחידה הבסיסית היחידה לקיבוץ תוכן. כל עמוד הוא ערימה אנכית של כרטיסים כאלה (אין grid/columns, הכל עמודה אחת).
2. **`.btn`** — כפתור primary (צבע accent — בורדו).
3. **`.btn-secondary`** — כפתור משני (outline/מוחלש).
4. **`.btn-danger`** — כפתור הרסני (מחיקה), אדום.
5. **`.mode-switch`** (role="radiogroup") — זוג/שלישיית כפתורי טאב "pill" להחלפת מצב (למשל Structured/AI בחיפוש, Graph/List בארכיטקטורה, Dark/Light בהגדרות). חוזר 3 פעמים במסכים שונים בדיוק באותה צורה.
6. **`search-row`** — שורת input + button אחד לצד השני. גם זה חוזר בהרבה מקומות (חיפוש, הוספת שירות, יצירת פרויקט...).
7. **`badge` / `badge-info` / `badge-warn` / `badge-danger` / `badge-muted`** — תגית קטנה מעוגלת לרמת לוג (TRACE/DEBUG/INFO/WARN/ERROR/FATAL).
8. **טבלת תוצאות** — `<table>` HTML רגילה, בלי סגנון עשיר (כותרות, שורות, hover).
9. **`.empty-state`** — קופסה עם טקסט "אין תוצאות" / "Coming soon" כשאין מה להציג.
10. **`.loading-row`** — טקסט טעינה פשוט (אין ספינר/סקלטון).
11. **`.alert-error`** — שורת שגיאה אדומה מתחת לטופס.
12. **`Modal`** — overlay כהה שקוף + כרטיס ממורכז (`modal-card`), נסגר בלחיצה על הרקע. משמש לעריכת Node/Connection בלבד.
13. **`legend-dot` / `legend-item`** — מקרא צבעים (לגרף הארכיטקטורה: UP/DEGRADED/DOWN/UNKNOWN).
14. **`connection-chip`** — "צ'יפ" (פילולה) להצגת שירות שנבחר בתור "מתחבר אל", עם כפתור X קטן להסרה.
15. **אייקונים** — סט SVG custom קטן (`icons.tsx` / `icons.svg`): Search, Flow, Dashboard, Uploads, Settings, Globe, Menu, Hide, Chevron.

**השפעה על redesign:** זה בעצם היעד — להחליף את כל 15 האלמנטים האלה בסט components עקבי, עם hierarchy חזותי אמיתי (כרגע הכל "כרטיס לבן עם h2" — אין הבדל חזותי בין "דף הגדרות שקט" ל"מסך חיפוש ראשי"). אין cards עם elevation/depth שונים, אין שימוש בצבעי accent-secondary/accent-tertiary שהוגדרו ב-CSS (טיל/ענבר) — הם מוגדרים אבל לא בשימוש בשום מקום בקוד בפועל (רק accent הבורדו בשימוש), מה שמחזיר חיוורון חזותי.

---

## 5. דף-by-דף — תיאור מלא

### 5.1 `/login` — מסך התחברות
**מטרה:** דמה (mock) — אין שדות משתמש/סיסמה, אין אימות אמיתי.

**תוכן:**
- מסך ממורכז (`auth-screen`), כרטיס יחיד (`auth-card`) באמצע המסך.
- לוגו "MS" בריבוע גדול (`auth-logo`).
- כותרת "ברוך שובך" (welcome) + תת-כותרת (subtitle).
- כפתור ראשי אחד: "Sign in" (primary btn, רוחב מלא כנראה).
- הודעת "mock notice" קטנה מתחת — טקסט שמסביר שזה דמה (למשל "אין צורך בפרטי התחברות אמיתיים").

**Flow:** לחיצה על Sign in → מדמה כניסה → ניווט אוטומטי ל-`/projects`. אם המשתמש כבר מחובר ומנסה לפתוח `/login` — redirect אוטומטי ל-`/projects` (אין אפשרות לראות שוב את מסך הלוגין כשמחוברים).

**הערה לעיצוב:** זה המסך היחיד שהוא "מחוץ לאפליקציה" (אין sidebar/topbar) — הזדמנות ל-branding/hero visual.

---

### 5.2 `/projects` — רשימת פרויקטים
**מטרה:** landing page לבחירת/יצירת פרויקט ("workspace") לפני כניסה לאפליקציה.

**תוכן (שני cards בעמודה אחת, ברוחב מוגבל, ממורכז בעמוד):**
1. **Header** (לא card — חלק מה-body): שם האפליקציה (h1) + תת-כותרת ("subtitle" — הסבר קצר), וכפתור "Sign out" secondary בפינה הנגדית (רק אם מחוברים).
2. **כרטיס "הפרויקטים שלי"**: כותרת + `<ul>` של פרויקטים, כל שורה = שם פרויקט + כפתור "Open" (btn ראשי) בקצה השני.
3. **כרטיס "פרויקט חדש"**: אם לא בתהליך יצירה — כפתור בודד "+ New project". בלחיצה נפתח inline input + autofocus + כפתור "Create" (disabled כל עוד השדה ריק) + כפתור "Cancel".

**Flow:**
- לחיצה על "Open" בפרויקט → `selectProject` (state גלובלי) → ניווט ל-`/p/:id/search`.
- יצירת פרויקט: טקסט → Enter או כפתור Create → פרויקט נוצר (לוקאלית, אין API אמיתי כנראה — ProjectsContext) → ניווט אוטומטי לתוך הפרויקט החדש ב-`/search`.
- Sign out → מחזיר ל-`/login`.

**הערה לעיצוב:** אין thumbnails/אייקונים לפרויקטים, אין metadata (מתי נוצר, כמה שירותים), אין empty state מיוחד אם אין פרויקטים כלל (כרגע רק `<ul>` ריקה) — הזדמנות לכרטיסי-פרויקט ויזואליים (grid של cards עם אייקון/צבע לכל פרויקט) במקום רשימת טקסט.

---

### 5.3 `/p/:id/search` — חיפוש לוגים (העמוד המרכזי)
**מטרה:** לחפש רשומות לוג משירותים, בשתי שיטות: חיפוש מילים מובנה, או שאלה בשפה חופשית שמתורגמת ע"י AI.

**מבנה (מלמעלה למטה, הכל בעמודה אחת):**

1. **Mode switch (טאבים)**: שני כפתורי pill — "Structured search" / "Ask in natural language" (role=radiogroup, aria-pressed). המצב הנבחר נשמר ב-localStorage (`moses.search.mode`) כך שנשאר בין רענונים.

2. **כרטיס טופס החיפוש** — תוכן תלוי-מצב:
   - **מצב Structured:** כותרת, hint טקסט (איך לכתוב מילות חיפוש), שורת input (free text, placeholder, autofocus, Enter=submit) + כפתור "Search" (btn ראשי). ה-input מפוצל למילים ע"י whitespace; שולח `from`=עכשיו-24ש, `to`=עכשיו (טווח קבוע של 24 שעות, לא נגיש ל-UI!).
   - **מצב AI/NL:** כותרת שונה, hint שונה, שורת input (placeholder לשאלה בשפה חופשית) + כפתור "Ask" (btn-secondary — שימו לב, שונה סמנטית מהראשון).

3. **שורת שגיאה** (`alert-error`) — מופיעה רק אם יש שגיאה (שדה ריק, או כשל API). מוצגת מתחת לכרטיס הטופס, לא בתוכו.

4. **כרטיס תוצאות** (`results-card`) — מופיע רק לאחר חיפוש ראשון (או כשטוען):
   - Header: כותרת תוצאות (שם שונה בין Structured ל-AI) + counter ("3 results").
   - שלושה states: `loading` (טקסט בלבד), `empty` (אין תוצאות), או **טבלה**:
     - עמודות: **Time** (מפורמט לפי שפה), **Service**, **Level** (badge צבעוני לפי severity: TRACE/DEBUG=מוחלש, INFO=כחול, WARN=כתום, ERROR/FATAL=אדום), **IP**, **Message** (raw log line, עמודה רחבה/חתוכה).

**State שנשמר:** מצב החיפוש (structured/ai) ב-localStorage; תוכן ה-inputs, תוצאות, שגיאה — state זמני בזיכרון בלבד (נאבד ברענון/ניווט).

**הערה לעיצוב:** אין date range picker (קבוע ל-24 שעות, נסתר מהמשתמש), אין filter לפי service/level/IP בטבלה, אין פאג'ינציה/infinite scroll, אין אפשרות ללחוץ על שורה כדי לראות פרטים מלאים (expand), אין highlighting של מילת החיפוש בתוך ה-message. זו "ask Splunk/Kibana" חוויה מצומצמת מאוד — הזדמנות גדולה לשדרוג: timeline/histogram מעל הטבלה, פילטרים צדיים, syntax highlighting ללוגים.

---

### 5.4 `/p/:id/flow` — ארכיטקטורה / מפת תקשורת (העמוד המורכב ביותר)
**מטרה:** להציג את כל השירותים (nodes) במערכת, מצבם (health), והחיבורים (קשתות/dependencies) בין שירות לשירות — גם כגרף אינטראקטיבי (drag & drop) וגם כרשימה.

**רענון אוטומטי:** polling כל 5 שניות ל-process state + connections (`/api/process/state`, `/api/process/connections`).

**מבנה (מלמעלה למטה):**

1. **כרטיס "הוסף שירות"**:
   - סגור כברירת מחדל: רק כפתור "+ Add service".
   - פתוח: טופס מלא —
     - שורה: input לשם השירות + select לסטטוס התחלתי (UP/DEGRADED/DOWN/UNKNOWN).
     - textarea לתיאור השירות (description, אופציונלי).
     - input ל-"Probe URL" + hint טקסט מסביר (URL שהמערכת תבדוק אוטומטית לבריאות השירות).
     - **ConnectionsPicker** (רק אם יש שירותים קיימים): תווית+hint, "צ'יפים" של שירותים שנבחרו (כל צ'יפ עם X להסרה), ו-`<select>` נפרד להוספת עוד שירות ל"מתחבר אל" (dropdown "תוספת אחת בכל פעם" — לא multi-select רגיל).
     - שגיאה אם השם ריק.
     - שורת כפתורים: "Add" (ראשי) + "Cancel" (משני) — Cancel מאפס את כל הטופס וסוגר אותו.

2. **Mode switch**: "Graph view" / "List view" — נשמר ב-localStorage (`moses.flow.view.<projectId>`, **לכל פרויקט בנפרד**).

3. **כרטיס תצוגה ראשית** (`flow-card`):
   - **Graph view:**
     - Hint-line מעל ("לחיצה על node/edge לעריכה").
     - `states` (error/loading/empty) כמו בחיפוש.
     - **ReactFlow canvas** (`flow-canvas`): nodes מרונדרים כ-divs בצבע status (UP=ירוק, DEGRADED=כתום, DOWN=אדום, UNKNOWN=אפור) עם שם השירות + טקסט סטטוס בתוך ה-node. edges = חיבורים, עם חץ (או חצים בשני הקצוות אם bidirectional), אנימציה (animated dash). Background grid + Controls (zoom/pan, בלי כפתור "interactive toggle"). מיקום כל node נשמר ב-localStorage **לכל פרויקט** (`moses.flow.positions.<projectId>`) — drag position "מנצח" position שמגיע מהשרת/polling, כך שגרירה לא "קופצת חזרה" כל 5 שניות.
     - **Legend** מתחת לגרף: 4 נקודות צבע + תווית (UP/DEGRADED/DOWN/UNKNOWN).
     - לחיצה על node → פותח **EditNodeForm** במודאל. לחיצה על edge → פותח **EditConnectionForm** במודאל.
   - **List view** (`ArchitectureListView`) — תחליף נגיש/טבלאי לגרף:
     - **Section 1 — Services**: רשימת accordion rows. כל שורה: chevron (פתח/סגור) + נקודת צבע סטטוס + שם שירות + תווית סטטוס טקסטואלית. בהרחבה (expand): תיאור השירות (או "no description"), שורת סטטוס של ה-probe (ראה סעיף 6), כפתור "Edit" (secondary) שפותח את אותו מודאל עריכה.
     - **Section 2 — Connections**: רשימת שורות קליקביליות, כל שורה: טקסט כיוון (template מוכן: "X → Y" או "X ↔ Y" עם שמות השירותים ממולאים), + תגית סוג תקשורת (HTTP/Kafka/וכו', אם הוזן), + תיאור (אם הוזן). לחיצה על שורה פותחת **EditConnectionForm**.

4. **EditNodeForm (מודאל)** — זהה בתוכן לטופס "הוסף שירות" אבל ל-edit: input שם + select סטטוס, textarea תיאור, input probe URL (+ שורת hint שמציגה את תוצאת ה-probe האחרונה בפועל — ראו סעיף 6), ConnectionsPicker (לרשימת שאר השירותים). שורת כפתורים: "Save" + "Cancel" בצד אחד, "Delete" (danger) בצד השני — Delete מבקש אישור `window.confirm` (דיאלוג דפדפן native, לא מודאל מעוצב!).

5. **EditConnectionForm (מודאל)**:
   - Select לכיוון (Source→Target / Target→Source / Bidirectional) — הטקסט בפועל ממלא את שמות השירותים האמיתיים בתוך המשפט.
   - Input לסוג תקשורת (communicationType — טקסט חופשי, למשל "REST", "Kafka").
   - Textarea לתיאור.
   - Save/Cancel/Delete (Delete עם `window.confirm` native גם כן).

**הערה לעיצוב:** זה המסך שהכי "מרגיש כמו טמפלט גנרי" — הגרף (ReactFlow) ברירת מחדל ללא עיצוב מותאם (nodes = קופסה צבעונית פשוטה, בלי אייקונים לפי סוג שירות, בלי thickness/style שונה לחיבורים לפי סוג תקשורת, בלי mini-map). ה-`window.confirm` לשני ה-deletes שובר לגמרי את ה-visual language (דיאלוג מערכת הדפדפן בתוך אפליקציה מעוצבת). List view ו-Graph view הם שני "universes" חזותיים נפרדים לגמרי של אותו דאטה — הזדמנות לאחד שפה (צבעי סטטוס/אייקונים חוזרים).

---

### 5.5 `/p/:id/dashboard` ו-`/p/:id/uploads` — "Coming soon"
שני ה-routes האלה מרונדרים בדיוק את אותה קומפוננטה גנרית (`ComingSoonPage`): כרטיס יחיד (`empty-state`) עם כותרת + פסקת גוף גנרית ("בקרוב..."). אין הבדל ביניהם מעבר לטקסט (אם יש בכלל טקסט שונה — כרגע כנראה טקסט זהה, generic). ה-Sidebar מראה עליהם נקודת "soon" קטנה.

**הערה לעיצוב:** הזדמנות ל-placeholder מעוצב יותר (illustration, preview מעומעם של מה שיבוא, buttons "notify me") במקום טקסט יבש.

---

### 5.6 `/p/:id/settings` — הגדרות
**מבנה (שני cards):**
1. **כרטיס "Appearance"**: כותרת + hint, ואז mode-switch (Dark/Light) — זהה ויזואלית ל-mode-switch של Search/Flow. ברירת מחדל: **Dark** (מוגדר בקוד, "spec: black background by default"). הבחירה נשמרת ב-localStorage (`moses.theme`) ומוחלת כ-`data-theme` על ה-`<html>`.
2. **כרטיס "Integration"**: כותרת + hint שמסביר מה זה, וכפתור-הורדה (`<a download>`, מעוצב כ-btn) שמוריד קובץ Markdown (`moses-monitoring-skill.md`) — מיועד לחיבור שירותים חיצוניים ל-MoSes (agent skill file).

**הערה לעיצוב:** עמוד מאוד מינימלי — שני cards בלבד, בלי profile/account settings, בלי ניהול משתמשים/הרשאות, בלי API keys viewer, בלי ניהול פרויקט (rename/delete project לא קיים בשום מקום ב-UI כולו!).

---

## 6. "Probe status" — התנהגות חוצה-עמודים

יש מנגנון שבודק אוטומטית בריאות שירות לפי URL (`probeUrl`) שהוגדר בטופס השירות. בכל מקום שבו מוצג תיאור שירות (EditNodeForm, ArchitectureListView) מופיעה שורת `hint` טקסטואלית שמסכמת: מתי נבדק לאחרונה, קוד תגובה HTTP, latency במ"ש, או הודעת שגיאה אם הבדיקה נכשלה. זה טקסט בלבד — **אין** ייצוג חזותי (אייקון/צבע/graph) לnhistoryה היסטוריה של ה-probe, רק הבדיקה האחרונה.

---

## 7. מודל דאטה (למה שהעיצוב צריך לתת מקום)

**LogEntry** (שורת חיפוש): `logId`, `timestamp`, `serviceName`, `logLevel` (0-5: TRACE..FATAL), `ip`, `rawMessage`.

**ProcessState** (node בגרף): `serviceName`, `processName`, `description`, `status` (UP/DEGRADED/DOWN/UNKNOWN), `cpuPercent`, `memoryMb`, `lastSeen`, `probeUrl`, `lastProbeAt`, `lastProbeStatusCode`, `lastProbeLatencyMs`, `lastProbeError`.
> **שים לב:** `cpuPercent`, `memoryMb`, `lastSeen` קיימים במודל אבל **לא מוצגים באף מקום ב-UI הנוכחי** (לא בגרף, לא ברשימה, לא במודאל עריכה) — הזדמנות ברורה להוסיף metrics ויזואליים (CPU/memory gauge, "last seen X ago") לכרטיס/node של שירות.

**Connection** (edge): `id`, `sourceService`, `targetService`, `direction` (SOURCE_TO_TARGET/TARGET_TO_SOURCE/BIDIRECTIONAL), `communicationType` (טקסט חופשי), `description`.

---

## 8. מערכת העיצוב הנוכחית (Design Tokens) — המצב הקיים

מוגדר כ-CSS variables ב-`index.css`, עם שני themes (dark=default, light):

| טוקן | Dark | Light | שימוש |
|---|---|---|---|
| `--bg` | `#0b0c0f` (שחור-אפור) | `#faf7f4` (קרם) | רקע כללי |
| `--surface` | `#16181d` | `#ffffff` | רקע כרטיסים |
| `--surface-muted` | `#1d2027` | `#f0ece7` | רקעי משנה (textarea וכו') |
| `--border` | `#2a2d35` | `#e3ded8` | גבולות |
| `--text` / `--text-muted` | `#e8e6e3` / `#9aa0ac` | `#1a1410` / `#6b645d` | טקסט |
| `--accent` | `#9b0515` (בורדו) | זהה | כפתורים ראשיים, הדגשות — **הצבע היחיד שמשמש בפועל כ-accent בכל ה-UI** |
| `--accent-secondary` (ענבר `#c9942e`) / `--accent-tertiary` (טיל `#1f6f78`) | מוגדרים | מוגדרים | **לא בשימוש בקוד בפועל** — "accent" יחיד לכל האפליקציה, גורם למראה חד-גוני/שטוח |
| `--up` / `--down` / `--degraded` / `--unknown` | ירוק/אדום/כתום/אפור | תואם | צבעי סטטוס שירות |
| `--danger` / `--warn` / `--info` + `-bg` | אדום/כתום/כחול בהיר | תואם | badges רמת לוג |
| `--radius` | `10px` | `10px` | פינות כרטיסים/כפתורים (אחיד לכל רכיב) |
| `--shadow` | shadow כהה עדין | shadow בהיר עדין | על כרטיסים |

**Typography:** `"Segoe UI", system-ui, -apple-system, "Arial Hebrew", Arial, sans-serif`, 15px/1.5 — **פונט מערכת גנרי**, אין font מותאם (Google Font וכו'), אין היררכיית גדלים ברורה (h1/h2 בלבד, בלי h3/body-large/caption מוגדרים כ-type scale).

**RTL/LTR:** האפליקציה תומכת בשתי השפות (`LanguageContext` + `strings.ts`), כיוון הדף משתנה לפי שפה (לא בדקתי direction attribute במפורש אבל יש תמיכה דו-כיוונית ב-CSS logical properties ככל הנראה — יש לשים לב ב-redesign לשמור alignment-agnostic, לא `left`/`right` קשיחים).

---

## 9. למה זה "מרגיש AI-generated" — אבחון תמציתי לקראת redesign

1. **כל מסך הוא עמודה של `.card`-ים לבנים/אפורים זהים** — אין hierarchy חזותי, elevation levels, גריד, או asymmetry. כל עמוד "נראה אותו דבר" (כותרת + hint + שורת פעולה).
2. **צבע accent יחיד (בורדו) לכל דבר** — כפתורים, הדגשות, active states — בלי ניואנסים (secondary/tertiary מוגדרים ב-CSS אבל לא בשימוש).
3. **רכיבי UI גנריים ב-100%** — `<select>`, `<input>`, `<textarea>` native ללא styling מתקדם, `window.confirm` של הדפדפן לפעולות מחיקה (שובר style באמצע flow מעוצב).
4. **אין איקונוגרפיה עשירה** — סט אייקונים קטן (9 אייקונים), בלי illustrations, בלי empty-state graphics, בלי avatars אמיתיים (רק ראשי תיבות טקסטואליים).
5. **טבלאות HTML רגילות** בלי sticky header, zebra striping עקבי, column sorting, density options.
6. **ReactFlow ב-default styling** בלי "look" מותאם לברנד (nodes = קופסה צבעונית פשוטה).
7. **אין motion/micro-interactions** — אין טרנזישנים על hover/active מעבר ל-`transition: background 0.15s`.
8. **Mode-switch "pill" חוזר 3 פעמים** באותה צורה בדיוק בלי וריאציה קונטקסטואלית.
9. **Metrics קיימים בדאטה (CPU/memory/lastSeen) אבל לא מוצגים** — אין dashboards/sparklines/gauges בשום מקום, אף על פי שזו אפליקציית מוניטורינג.

---

## 10. סיכום להעברה למנוע עיצוב אחר

כדי לעצב מחדש, המנוע השני צריך לדעת:
- **5 מסכים פנימיים** (Search, Flow/Architecture graph+list, Dashboard-placeholder, Uploads-placeholder, Settings) + **2 מסכי auth/onboarding** (Login, Projects list) — כולם תחת shell קבוע עם sidebar+topbar.
- **15 אבני בניין חוזרות** (סעיף 4) שצריך להמציא להן שפה עיצובית עקבית.
- **מודל דאטה עם metrics לא מנוצלים** (CPU/memory/lastSeen של שירותים) שאפשר למנף ל-dashboard אמיתי.
- **מודאלים לעריכת node/connection**, עם delete flow שצריך UI מאושר (להחליף את `window.confirm`).
- **Dark=default, Light optional**, RTL+LTR דו-לשוני, accent בורדו יחיד שאפשר/כדאי להרחיב לפלטה עשירה יותר (יש כבר secondary/tertiary מוגדרים ולא בשימוש — חומר גלם מוכן).
- **אין branding graphics/illustrations בכלל** — מקום פנוי מוחלט לזה.

מסמך האפיון המקורי (אם רלוונטי כחומר רקע נוסף) נמצא ב: `frontend/מסמך אפיון.docx`.
