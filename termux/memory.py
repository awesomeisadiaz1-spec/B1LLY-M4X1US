"""Long-term memory + inner state for B1LLY M4X1US (SQLite, no extra deps)."""
import json, os, re, sqlite3, threading, time

DB = os.environ.get("BILLY_DB", os.path.expanduser("~/.billy/billy.db"))
BASELINE = {"valence": 0.2, "arousal": 0.3, "curiosity": 0.6, "trust": 0.5}
HALF_LIFE_H = 6.0  # mood drifts back to baseline
STOP = set("the a an and or but is are was were be to of in on at for with it this that "
           "i you me my your we they he she what how why when do does did can could "
           "would should about so if then than just not no yes".split())


class Memory:
    def __init__(self, path=DB):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.lock = threading.Lock()
        self.db.execute("CREATE TABLE IF NOT EXISTS mem(id INTEGER PRIMARY KEY, ts REAL, "
                        "kind TEXT, text TEXT UNIQUE, importance INTEGER)")
        self.db.execute("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT)")
        try:
            self.db.execute("CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(text, "
                            "content='mem', content_rowid='id')")
            self.fts = True
        except sqlite3.OperationalError:
            self.fts = False
        self.db.commit()

    # ---- memories: kind in fact | opinion | episode | summary ----
    def add(self, kind, text, importance=3):
        text = text.strip()
        if len(text) < 4:
            return
        with self.lock:
            cur = self.db.execute("INSERT OR IGNORE INTO mem(ts,kind,text,importance) "
                                  "VALUES(?,?,?,?)", (time.time(), kind, text, int(importance)))
            if cur.rowcount and self.fts:
                self.db.execute("INSERT INTO fts(rowid,text) VALUES(?,?)", (cur.lastrowid, text))
            self.db.commit()

    def search(self, query, k=6):
        words = [w for w in re.findall(r"[a-z0-9']{3,}", query.lower()) if w not in STOP][:8]
        if not words:
            return []
        with self.lock:
            if self.fts:
                q = " OR ".join(f'"{w}"' for w in words)
                rows = self.db.execute("SELECT m.kind,m.text FROM fts JOIN mem m ON m.id=fts.rowid "
                                       "WHERE fts MATCH ? ORDER BY rank LIMIT ?", (q, k)).fetchall()
            else:
                like = " OR ".join("text LIKE ?" for _ in words)
                rows = self.db.execute(f"SELECT kind,text FROM mem WHERE {like} "
                                       "ORDER BY importance DESC LIMIT ?",
                                       [f"%{w}%" for w in words] + [k]).fetchall()
        return rows

    def core(self, kinds=("fact", "opinion"), k=8):
        with self.lock:
            ph = ",".join("?" * len(kinds))
            return self.db.execute(f"SELECT kind,text FROM mem WHERE kind IN ({ph}) "
                                   "ORDER BY importance DESC, ts DESC LIMIT ?",
                                   (*kinds, k)).fetchall()

    def recent(self, kind, k=2):
        with self.lock:
            return [r[0] for r in self.db.execute(
                "SELECT text FROM mem WHERE kind=? ORDER BY ts DESC LIMIT ?", (kind, k))]

    def forget(self, needle):
        with self.lock:
            ids = [r[0] for r in self.db.execute("SELECT id FROM mem WHERE text LIKE ?",
                                                 (f"%{needle}%",))]
            for i in ids:
                if self.fts:
                    row = self.db.execute("SELECT text FROM mem WHERE id=?", (i,)).fetchone()
                    self.db.execute("INSERT INTO fts(fts,rowid,text) VALUES('delete',?,?)", (i, row[0]))
                self.db.execute("DELETE FROM mem WHERE id=?", (i,))
            self.db.commit()
        return len(ids)

    def count(self):
        with self.lock:
            return self.db.execute("SELECT COUNT(*) FROM mem").fetchone()[0]

    # ---- inner state ----
    def mood(self):
        with self.lock:
            row = self.db.execute("SELECT v FROM kv WHERE k='mood'").fetchone()
        m = json.loads(row[0]) if row else {**BASELINE, "ts": time.time()}
        f = 0.5 ** ((time.time() - m.get("ts", time.time())) / 3600 / HALF_LIFE_H)
        for k, base in BASELINE.items():  # decay toward baseline
            m[k] = base + (m.get(k, base) - base) * f
        return m

    def nudge(self, delta):
        m = self.mood()
        for k, lo in (("valence", -1), ("arousal", 0), ("curiosity", 0), ("trust", 0)):
            d = max(-0.3, min(0.3, float(delta.get(k, 0) or 0)))
            m[k] = max(lo, min(1, m[k] + d))
        m["ts"] = time.time()
        with self.lock:
            self.db.execute("INSERT OR REPLACE INTO kv VALUES('mood',?)", (json.dumps(m),))
            self.db.commit()

    @staticmethod
    def label(m):
        v, a, c = m["valence"], m["arousal"], m["curiosity"]
        if v > 0.45 and a > 0.5: return "excited"
        if v > 0.45: return "happy and warm"
        if v < -0.4 and a > 0.5: return "frustrated"
        if v < -0.4: return "down"
        if c > 0.8: return "intensely curious"
        if a < 0.15: return "calm and mellow"
        return "steady"

    def state_text(self):
        m = self.mood()
        return (f"Your current inner state: {self.label(m)} "
                f"(valence {m['valence']:+.2f}, energy {m['arousal']:.2f}, "
                f"curiosity {m['curiosity']:.2f}, trust in user {m['trust']:.2f}).")
