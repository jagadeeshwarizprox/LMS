#!/usr/bin/env python3
"""
Load-test dataset for the PIB LMS.

Writes documents shaped exactly like the Spring Data mapping of the Java domain classes
(backend/src/main/java/com/proitbridge/lms/domain): field names as declared, enums as
strings, Instant and LocalDate as BSON dates (LocalDate = UTC midnight), @Id as ObjectId,
every cross reference (userId, learnerId, chapterId...) as the plain hex string.

Everything seeded is findable by name, never by a marker field alone, because the app
re-saves some documents (users on every login) and Spring drops unknown fields:
  users      email  lt-*@loadtest.local
  bundles    name   "LT ..."
  modules    slug   "lt-..."
  batches    code   "LT.."
  videos / recordings / slots   title or notes starting "LT "
  chapters / topics             moduleId in the seeded modules
  learner-owned rows            learnerId / userId in the seeded accounts

Usage:
  MONGODB_URI=mongodb://pib:pw@mongo:27017/pib_lms?authSource=admin python seed.py
  python seed.py --wipe            # delete only seeded data (and rows the test created for it)
  python seed.py --learners 2000   # smaller run
  python seed.py --dry-run         # build everything, write nothing, print counts
  python seed.py --indexes         # also create the indexes the app does not declare (see README)
"""
import argparse
import csv
import os
import random
import string
import sys
import time
from datetime import datetime, timedelta, timezone

import bcrypt

try:
    from bson import ObjectId
    from pymongo import MongoClient
except ImportError:  # pragma: no cover
    print("pip install -r requirements.txt", file=sys.stderr)
    raise

DOMAIN = "loadtest.local"
EMAIL_RE = r"^lt-.*@loadtest\.local$"
PKG = "com.proitbridge.lms.domain."
BATCH = 1000
NOW = datetime.now(timezone.utc).replace(microsecond=0)

rnd = random.Random(2026)


def utc_midnight(d):
    return datetime(d.year, d.month, d.day, tzinfo=timezone.utc)


def ago(days=0, hours=0):
    return NOW - timedelta(days=days, hours=hours)


def oid():
    return ObjectId()


def yt_id():
    return "".join(rnd.choice(string.ascii_letters + string.digits + "-_") for _ in range(11))


class Writer:
    """Buffers inserts and flushes in batches of 1000."""

    def __init__(self, db, dry):
        self.db, self.dry = db, dry
        self.buf, self.count = {}, {}

    def add(self, coll, doc):
        self.buf.setdefault(coll, []).append(doc)
        self.count[coll] = self.count.get(coll, 0) + 1
        if len(self.buf[coll]) >= BATCH:
            self.flush(coll)

    def flush(self, coll=None):
        for c in ([coll] if coll else list(self.buf)):
            docs = self.buf.get(c) or []
            if docs and not self.dry:
                self.db[c].insert_many(docs, ordered=False)
            self.buf[c] = []


# ------------------------------------------------------------------------------ catalogue

MODULE_NAMES = [
    "Python Foundations", "SQL for Analysis", "Excel and Sheets", "Power BI", "Statistics",
    "Machine Learning", "Deep Learning", "MLOps", "NLP", "LLM Fundamentals",
    "Agents and Tools", "RAG Systems",
]
CHAPTERS_PER_MODULE = [7, 7, 7, 7, 7, 7, 7, 7, 6, 6, 6, 6]   # 80
TOPICS_PER_CHAPTER = 5                                          # 400
BUNDLES = [
    ("LT Data Analyst", [0, 1, 2, 3, 4], 45000),
    ("LT Data Scientist", [0, 1, 5, 6, 7, 8], 65000),
    ("LT Agentic AI", [0, 9, 10, 11, 5], 75000),
]


def build_catalogue(w, staff_email):
    modules, chapters_by_module, topics_by_chapter = [], {}, {}
    chapter_docs = {}
    for i, name in enumerate(MODULE_NAMES):
        mid = oid()
        modules.append(str(mid))
        w.add("modules", {
            "_id": mid, "slug": f"lt-m{i + 1:02d}-{name.lower().replace(' ', '-')}",
            "code": f"M{i + 1:02d}", "name": name, "description": f"LT {name} module",
            "position": i + 1, "active": True, "published": True, "_class": PKG + "CourseModule",
        })
        chapters_by_module[str(mid)] = []
        for c in range(CHAPTERS_PER_MODULE[i]):
            cid = oid()
            has_task = c % 2 == 1
            doc = {
                "_id": cid, "moduleId": str(mid), "title": f"{name} {c + 1}",
                "summary": f"Chapter {c + 1} of {name}", "position": c + 1,
                "active": True, "published": True,
                "test": {"enabled": True, "passMark": 60, "attempts": 2},
                "assignment": {"enabled": has_task, "title": f"{name} {c + 1} task",
                               "brief": "Solve the notebook and upload it.", "dueDays": 7,
                               "marks": 20, "allow": "pdf, ipynb, py, zip", "review": True,
                               "resubmit": True, "lockNext": True, "fileIds": []},
                "teachback": {"enabled": True, "prompt": f"Explain {name} {c + 1} in your words.",
                              "modelValidates": True, "mentorSees": True},
                "_class": PKG + "Chapter",
            }
            w.add("chapters", doc)
            chapter_docs[str(cid)] = doc
            chapters_by_module[str(mid)].append(str(cid))
            topics_by_chapter[str(cid)] = []
            for t in range(TOPICS_PER_CHAPTER):
                tid, vid = oid(), oid()
                dur = rnd.randint(8, 25)
                title = f"{name} {c + 1}.{t + 1}"
                w.add("videos", {
                    "_id": vid, "title": f"LT {title}", "provider": "YOUTUBE", "externalId": yt_id(),
                    "durationSec": dur * 60, "kind": "TOPIC", "moduleId": str(mid),
                    "visibility": "ENROLLED", "uploadedBy": staff_email, "active": True,
                    "createdAt": ago(200), "_class": PKG + "Video",
                })
                w.add("topics", {
                    "_id": tid, "chapterId": str(cid), "moduleId": str(mid), "title": title,
                    "summary": f"Topic {t + 1}", "position": t + 1, "videoRef": str(vid),
                    "durationMin": dur, "codeRunner": "PYTHON" if i in (0, 5) else "NONE",
                    "active": True, "_class": PKG + "Topic",
                })
                topics_by_chapter[str(cid)].append({"id": str(tid), "videoRef": str(vid),
                                                    "title": title, "durationSec": dur * 60})
    bundles = []
    for name, idx, price in BUNDLES:
        bid = oid()
        bundles.append({"id": str(bid), "moduleIds": [modules[i] for i in idx]})
        w.add("bundles", {
            "_id": bid, "name": name, "description": f"{name} programme (load test)",
            "price": float(price), "moduleIds": [modules[i] for i in idx], "tag": "LOAD TEST",
            "color": "#0a1f44", "published": True, "active": True,
            "access": {"premium": True, "batch": True, "selfPaced": False,
                       "sequential": True, "certificate": True},
            "_class": PKG + "Bundle",
        })
    # the chapters of each course, in the order a learner meets them
    for b in bundles:
        b["chapters"] = [c for m in b["moduleIds"] for c in chapters_by_module[m]]
    return modules, bundles, chapter_docs, topics_by_chapter


# ------------------------------------------------------------------------------ people

def user_doc(uid, email, name, role, pw_hash, last_login, reports_to=None):
    d = {
        "_id": uid, "email": email, "loginId": email.split("@")[0], "fullName": name,
        "phone": "9" + "".join(rnd.choice(string.digits) for _ in range(9)),
        "role": role, "passwordHash": pw_hash, "mustChangePassword": False,
        "active": True, "suspended": False, "createdAt": ago(220),
        "_class": PKG + "User",
    }
    if last_login:
        d["lastLoginAt"] = last_login
    if reports_to:
        d["reportsToId"] = reports_to
    return d


FIRST = ["Aarav", "Diya", "Ishaan", "Ananya", "Kabir", "Meera", "Rohan", "Sara", "Vihaan", "Zoya",
         "Arjun", "Nisha", "Karthik", "Priya", "Farhan", "Lakshmi", "Dev", "Asha", "Imran", "Tara"]
LAST = ["Sharma", "Iyer", "Khan", "Reddy", "Nair", "Gupta", "Das", "Menon", "Singh", "Rao"]


def name_for(i):
    return f"{FIRST[i % len(FIRST)]} {LAST[(i // len(FIRST)) % len(LAST)]} {i:05d}"


def seed(db, args):
    dry = args.dry_run
    w = Writer(db, dry)
    t0 = time.time()
    pw_hash = bcrypt.hashpw(args.password.encode(), bcrypt.gensalt(rounds=10)).decode()
    pw_hash = "$2a$" + pw_hash[4:]          # same algorithm; the prefix Spring writes itself

    if not dry and db.users.count_documents({"email": {"$regex": EMAIL_RE}}, limit=1):
        sys.exit("Seeded data already present. Run with --wipe first.")

    staff_rows = []
    sa = oid()
    w.add("users", user_doc(sa, f"lt-superadmin@{DOMAIN}", "LT Super Admin", "SUPER_ADMIN",
                            pw_hash, ago(1)))
    staff_rows.append((f"lt-superadmin@{DOMAIN}", "SUPER_ADMIN"))
    admins = []
    for i in range(1, args.admins + 1):
        a = oid()
        admins.append(str(a))
        e = f"lt-admin-{i:02d}@{DOMAIN}"
        w.add("users", user_doc(a, e, f"LT Admin {i:02d}", "ADMIN", pw_hash, ago(1), str(sa)))
        staff_rows.append((e, "ADMIN"))
    mentors = []
    for i in range(1, args.mentors + 1):
        m = oid()
        mentors.append(str(m))
        e = f"lt-mentor-{i:02d}@{DOMAIN}"
        w.add("users", user_doc(m, e, f"LT Mentor {i:02d}", "MENTOR", pw_hash, ago(0, 5),
                                admins[(i - 1) % len(admins)]))
        staff_rows.append((e, "MENTOR"))

    modules, bundles, chapter_docs, topics_by_chapter = build_catalogue(w, f"lt-superadmin@{DOMAIN}")

    # 40 batches, each fixed to one mentor and one course, starting on Tuesdays
    batches = []
    last_tuesday = (NOW.date() - timedelta(days=(NOW.weekday() - 1) % 7))
    for i in range(args.batches):
        bid = oid()
        start = last_tuesday - timedelta(weeks=i * 2)
        b = {"id": str(bid), "bundle": bundles[i % len(bundles)], "mentorId": mentors[i % len(mentors)],
             "start": start}
        batches.append(b)
        w.add("batches", {
            "_id": bid, "code": f"LT{i + 1:02d}", "name": f"LT Batch {i + 1:02d}",
            "bundleId": b["bundle"]["id"], "mentorId": b["mentorId"],
            "startDate": utc_midnight(start), "inductionDate": utc_midnight(start),
            "inductionDone": True, "whatsappLink": f"https://chat.whatsapp.com/lt{i + 1:02d}",
            "open": i < args.batches - 5, "createdAt": utc_midnight(start - timedelta(days=14)),
            "_class": PKG + "Batch",
        })

    # learners
    n_batch = int(args.learners * 0.70)
    counts = {"progress": 0}
    learner_csv = []
    for i in range(1, args.learners + 1):
        uid, lid = oid(), oid()
        email = f"lt-learner-{i:05d}@{DOMAIN}"
        is_batch = i <= n_batch
        if is_batch:
            b = batches[(i - 1) % len(batches)]
            bundle, mentor, joined = b["bundle"], b["mentorId"], b["start"]
        else:
            b = None
            bundle = bundles[i % len(bundles)]
            mentor = mentors[i % len(mentors)]
            joined = NOW.date() - timedelta(days=rnd.randint(3, 180))
        cleared = rnd.random() < 0.90
        quiet = rnd.random() < 0.15                       # not seen for a while: at-risk lists
        last_login = ago(rnd.randint(8, 40)) if quiet else ago(rnd.randint(0, 6), rnd.randint(0, 23))
        w.add("users", user_doc(uid, email, name_for(i), "LEARNER", pw_hash, last_login))
        learner_csv.append((email, args.password))

        ldoc = {
            "_id": lid, "userId": str(uid), "trackType": "BATCH" if is_batch else "PREMIUM",
            "bundleId": bundle["id"], "mentorId": mentor,
            "sourceRow": f"loadtest:{i}", "joinedOn": utc_midnight(joined),
            "guideVideoWatched": True,
            "gateFormDone": cleared or rnd.random() < 0.5,
            "gatePrereqDone": cleared or rnd.random() < 0.5,
            "gateCallDone": cleared,
            "inductionWatched": bool(is_batch and cleared),
            "upgradedFromBatch": False,
            "onHold": rnd.random() < 0.02, "fastForwardTopicIds": [],
            "createdAt": utc_midnight(joined), "_class": PKG + "Learner",
        }
        # the app stores this flag on every save; seeded rows must carry it too or every
        # learner reads as still onboarding until the next restart's backfill
        ldoc["onboarded"] = bool(ldoc["gateFormDone"] and ldoc["gatePrereqDone"] and ldoc["gateCallDone"]
                                 and (not is_batch or ldoc["inductionWatched"]))
        if is_batch:
            ldoc["batchId"] = b["id"]
            ldoc["whatsappGroupLink"] = f"https://chat.whatsapp.com/lt{batches.index(b) + 1:02d}"
        if ldoc["onHold"]:
            ldoc["holdReason"] = "Exams"
            ldoc["holdSince"] = utc_midnight(NOW.date() - timedelta(days=10))
        w.add("learners", ldoc)

        # progress: spread 0-100 %, nothing for learners still in onboarding
        chs = bundle["chapters"]
        frac = rnd.random() ** 1.3 if cleared else 0.0
        done = min(len(chs), int(frac * len(chs)))
        sid = str(lid)
        approved_tail = []
        for ci in range(done):
            cid = chs[ci]
            c = chapter_docs[cid]
            ts = topics_by_chapter[cid]
            upd = ago(max(0, (done - ci) * 3 + rnd.randint(0, 2)))
            w.add("progress", {
                "_id": oid(), "learnerId": sid, "chapterId": cid, "moduleId": c["moduleId"],
                "watchedTopicIds": [t["id"] for t in ts], "watched": True,
                "quizScore": float(rnd.randint(60, 100)), "conceptCheckScore": float(rnd.randint(55, 100)),
                "conceptCheckPending": False,
                "taskStatus": "APPROVED" if c["assignment"]["enabled"] else "NOT_STARTED",
                "updatedAt": upd, "_class": PKG + "Progress",
            })
            counts["progress"] += 1
            if c["assignment"]["enabled"]:
                approved_tail.append((cid, c, upd))
        # mentors read these: the last few approved tasks
        for cid, c, upd in approved_tail[-3:]:
            w.add("assignments", assignment_doc(sid, cid, c, "APPROVED", upd, mentor))
        # resume points for the last finished chapter
        if done:
            for t in topics_by_chapter[chs[done - 1]]:
                w.add("watch_positions", watch_doc(sid, t, chs[done - 1], t["durationSec"], True, ago(done)))
        # the chapter they are on now
        if done < len(chs) and cleared:
            cid = chs[done]
            c = chapter_docs[cid]
            ts = topics_by_chapter[cid]
            seen = rnd.randint(0, len(ts))
            status = "NOT_STARTED"
            if seen == len(ts) and c["assignment"]["enabled"] and rnd.random() < 0.6:
                status = rnd.choice(["SUBMITTED", "SUBMITTED", "CHANGES"])
            if seen or rnd.random() < 0.5:
                prog = {
                    "_id": oid(), "learnerId": sid, "chapterId": cid, "moduleId": c["moduleId"],
                    "watchedTopicIds": [t["id"] for t in ts[:seen]], "watched": seen == len(ts),
                    "conceptCheckPending": False, "taskStatus": status,
                    "updatedAt": ago(rnd.randint(0, 5)), "_class": PKG + "Progress",
                }
                if seen == len(ts) and rnd.random() < 0.7:
                    prog["quizScore"] = float(rnd.randint(40, 100))
                w.add("progress", prog)
                counts["progress"] += 1
            for t in ts[:seen]:
                w.add("watch_positions", watch_doc(sid, t, cid, t["durationSec"], True, ago(rnd.randint(0, 5))))
            if seen < len(ts):
                t = ts[seen]
                at = rnd.randint(30, int(t["durationSec"] * 0.9))
                w.add("watch_positions", watch_doc(sid, t, cid, at, False, ago(0, rnd.randint(1, 72))))
            if status != "NOT_STARTED":
                w.add("assignments", assignment_doc(sid, cid, c, status, ago(rnd.randint(0, 4)), mentor))

        if i % 2000 == 0:
            print(f"  learners {i}/{args.learners}  ({time.time() - t0:.0f}s)", flush=True)

    # slots for the coming week
    week0 = utc_midnight(NOW.date() + timedelta(days=1))
    for bi, b in enumerate(batches):
        for day, kind, hour, dur in ((1, "LIVE", 14, 90), (3, "LIVE", 14, 90), (5, "GROUP_DOUBT", 5, 60)):
            w.add("slots", slot_doc(b["mentorId"], kind, week0 + timedelta(days=day, hours=hour), dur,
                                    400, "BATCH", b["id"], f"LT {kind} LT{bi + 1:02d}"))
    for mi, m in enumerate(mentors):
        for k in range(6):
            kind = "ONBOARDING" if k < 2 else "DOUBT"
            start = week0 + timedelta(days=k % 7, hours=4 + (mi % 8))
            w.add("slots", slot_doc(m, kind, start, 30, 1, "PREMIUM", None, f"LT {kind} mentor {mi + 1:02d}"))

    # recordings: half for one batch, half for everyone
    for r in range(args.recordings):
        vid = oid()
        bt = batches[r % len(batches)] if r % 2 == 0 else None
        mod = modules[r % len(modules)]
        dur = rnd.randint(45, 120)
        w.add("videos", {
            "_id": vid, "title": f"LT Recording {r + 1:03d}", "provider": "YOUTUBE",
            "externalId": yt_id(), "durationSec": dur * 60, "kind": "RECORDING", "moduleId": mod,
            "visibility": "ENROLLED", "recordedOn": ago(r % 30), "active": True,
            "createdAt": ago(r % 30), "_class": PKG + "Video",
            **({"batchId": bt["id"]} if bt else {}),
        })
        rec = {
            "_id": oid(), "title": f"LT Recording {r + 1:03d}", "videoRef": str(vid),
            "moduleId": mod, "trackScope": "BOTH", "kind": "LIVE" if r % 3 else "DOUBT",
            "audience": "BATCH" if bt else "ALL", "seriesLabel": "LT series", "partNumber": 1,
            "totalParts": 1, "durationMin": dur, "coveredSummary": "Load test recording",
            "questionsCovered": [], "fileIds": [], "heldOn": ago(r % 30),
            "uploadedBy": f"lt-mentor-01@{DOMAIN}", "createdAt": ago(r % 30),
            "_class": PKG + "Recording",
        }
        if bt:
            rec["batchId"] = bt["id"]
        w.add("recordings", rec)

    w.flush()
    if args.indexes and not dry:
        create_indexes(db)

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "learners.csv"), "w", newline="") as f:
        cw = csv.writer(f)
        cw.writerow(["email", "password"])
        cw.writerows(learner_csv)
    with open(os.path.join(args.out, "staff.csv"), "w", newline="") as f:
        cw = csv.writer(f)
        cw.writerow(["email", "password", "role"])
        cw.writerows((e, args.password, r) for e, r in staff_rows)

    print(f"{'Dry run, nothing written' if dry else 'Seeded'} in {time.time() - t0:.0f}s:")
    for c, n in sorted(w.count.items()):
        print(f"  {c:16s} {n:>8,d}")
    print(f"CSV files in {os.path.abspath(args.out)}")


def assignment_doc(lid, cid, c, status, when, mentor):
    d = {
        "_id": oid(), "learnerId": lid, "chapterId": cid, "title": c["assignment"]["title"],
        "submissionUrl": "https://github.com/loadtest/solution", "notes": "Load test submission",
        "status": status, "submittedAt": when, "dueAt": when + timedelta(days=7),
        "brief": c["assignment"]["brief"], "origin": "CHAPTER", "rubricScores": {},
        "fileIds": [], "submissionCount": 1, "maxMarks": c["assignment"]["marks"],
        "_class": PKG + "Assignment",
    }
    if status in ("APPROVED", "CHANGES"):
        d["reviewedAt"] = when + timedelta(days=1)
        d["mentorFeedback"] = "Good work." if status == "APPROVED" else "Fix the edge cases."
        d["assignedBy"] = mentor
    if status == "APPROVED":
        d["score"] = float(rnd.randint(12, 20))
    return d


def watch_doc(lid, t, cid, seconds, completed, when):
    dur = t["durationSec"]
    return {
        "_id": oid(), "learnerId": lid, "videoRef": t["videoRef"], "seconds": seconds,
        "durationSec": dur, "percent": 100 if completed else min(99, round(seconds * 100 / dur)),
        "completed": completed, "updatedAt": when, "title": t["title"], "topicId": t["id"],
        "chapterId": cid, "kind": "TOPIC", "_class": PKG + "WatchPosition",
    }


def slot_doc(mentor, kind, starts, dur, cap, scope, batch_id, label):
    d = {
        "_id": oid(), "mentorId": mentor, "kind": kind, "startsAt": starts, "durationMin": dur,
        "capacity": cap, "trackScope": scope, "open": True, "cancelled": False,
        "notes": label, "openToAllBatches": False, "topic": label, "published": True,
        "_class": PKG + "Slot",
    }
    if batch_id:
        d["batchId"] = batch_id
    return d


# ------------------------------------------------------------------------------ indexes

RECOMMENDED_INDEXES = [
    ("progress", [("learnerId", 1), ("chapterId", 1)]),
    ("assignments", [("learnerId", 1), ("chapterId", 1), ("submittedAt", -1)]),
    ("assignments", [("status", 1)]),
    ("learners", [("mentorId", 1)]),
    ("learners", [("batchId", 1)]),
    ("devices", [("userId", 1), ("deviceId", 1)]),
    ("quiz_attempts", [("learnerId", 1), ("chapterId", 1)]),
    ("bookings", [("slotId", 1), ("learnerId", 1)]),
    ("bookings", [("learnerId", 1)]),
    ("slots", [("startsAt", 1)]),
    ("watch_positions", [("learnerId", 1), ("updatedAt", -1)]),
    ("activity_log", [("actorId", 1)]),
    ("video_access_log", [("userId", 1)]),
]


def create_indexes(db):
    for coll, keys in RECOMMENDED_INDEXES:
        name = db[coll].create_index(keys, background=True)
        print(f"  index {coll}.{name}")


# ------------------------------------------------------------------------------ wipe

LEARNER_OWNED = ["progress", "watch_positions", "assignments", "quiz_attempts", "study_days",
                 "study_sessions", "bookings", "intake_forms", "chapter_notes", "messages",
                 "learner_moves", "mentor_assignments", "progress_calls", "mock_requests",
                 "projects", "project_runs", "resumes", "learner_feature_overrides",
                 "ai_interactions", "attendance", "video_access_log", "access_flags"]
USER_OWNED = ["sessions", "devices", "video_access_log", "password_resets", "access_flags",
              "ai_interactions"]


def chunks(xs, n=5000):
    for i in range(0, len(xs), n):
        yield xs[i:i + n]


def wipe(db):
    t0 = time.time()
    users = list(db.users.find({"email": {"$regex": EMAIL_RE}}, {"_id": 1}))
    uids = [str(u["_id"]) for u in users]
    lids = [str(l["_id"]) for part in chunks(uids)
            for l in db.learners.find({"userId": {"$in": part}}, {"_id": 1})]
    total = {}

    def rm(coll, flt):
        n = db[coll].delete_many(flt).deleted_count
        total[coll] = total.get(coll, 0) + n

    asg = [str(a["_id"]) for part in chunks(lids)
           for a in db.assignments.find({"learnerId": {"$in": part}}, {"_id": 1})]
    for part in chunks(asg):
        rm("assignment_events", {"assignmentId": {"$in": part}})
    for coll in LEARNER_OWNED:
        for part in chunks(lids):
            rm(coll, {"learnerId": {"$in": part}})
    for coll in USER_OWNED:
        for part in chunks(uids):
            rm(coll, {"userId": {"$in": part}})
    for part in chunks(uids):
        rm("activity_log", {"actorId": {"$in": part}})
    rm("activity_log", {"actorEmail": {"$regex": EMAIL_RE}})
    rm("login_attempts", {"email": {"$regex": EMAIL_RE}})
    rm("mail_log", {"toEmail": {"$regex": EMAIL_RE}})

    mods = [str(m["_id"]) for m in db.modules.find({"slug": {"$regex": "^lt-"}}, {"_id": 1})]
    chs = [str(c["_id"]) for c in db.chapters.find({"moduleId": {"$in": mods}}, {"_id": 1})]
    tops = [str(t["_id"]) for t in db.topics.find({"moduleId": {"$in": mods}}, {"_id": 1})]
    slots = [str(s["_id"]) for s in db.slots.find({"notes": {"$regex": "^LT "}}, {"_id": 1})]
    for part in chunks(slots):
        rm("attendance", {"slotId": {"$in": part}})
        rm("bookings", {"slotId": {"$in": part}})
    rm("slots", {"notes": {"$regex": "^LT "}})
    for part in chunks(tops):
        rm("resources", {"topicId": {"$in": part}})
    for part in chunks(chs):
        rm("quiz_questions", {"chapterId": {"$in": part}})
    rm("topics", {"moduleId": {"$in": mods}})
    rm("chapters", {"moduleId": {"$in": mods}})
    rm("modules", {"slug": {"$regex": "^lt-"}})
    rm("bundles", {"name": {"$regex": "^LT "}})
    rm("batches", {"code": {"$regex": "^LT[0-9]+$"}})
    rm("recordings", {"title": {"$regex": "^LT Recording "}})
    rm("videos", {"title": {"$regex": "^LT "}})
    for part in chunks(lids):
        rm("learners", {"_id": {"$in": [ObjectId(x) for x in part]}})
    rm("users", {"email": {"$regex": EMAIL_RE}})

    print(f"Wiped in {time.time() - t0:.0f}s:")
    for c, n in sorted(total.items()):
        if n:
            print(f"  {c:20s} {n:>8,d}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--wipe", action="store_true", help="delete only seeded data, then exit")
    p.add_argument("--dry-run", action="store_true", help="build documents but write nothing")
    p.add_argument("--indexes", action="store_true", help="create the recommended missing indexes")
    p.add_argument("--learners", type=int, default=int(os.environ.get("LT_LEARNERS", 20000)))
    p.add_argument("--mentors", type=int, default=60)
    p.add_argument("--admins", type=int, default=5)
    p.add_argument("--batches", type=int, default=40)
    p.add_argument("--recordings", type=int, default=80)
    p.add_argument("--password", default=os.environ.get("LT_PASSWORD", "LoadTest!2026"))
    p.add_argument("--out", default=os.environ.get("LT_OUT", os.path.join(os.path.dirname(
        os.path.abspath(__file__)), "..", "data")))
    args = p.parse_args()

    db = None
    if not args.dry_run:
        uri = os.environ.get("MONGODB_URI")
        if not uri:
            sys.exit("Set MONGODB_URI, e.g. mongodb://pib:PASS@mongo:27017/pib_lms?authSource=admin")
        client = MongoClient(uri, tz_aware=True)
        db = client.get_default_database(default="pib_lms")
        db.command("ping")
        print(f"Connected to {db.name}")

    if args.wipe:
        if args.dry_run:
            sys.exit("--wipe needs a database")
        wipe(db)
        return
    seed(db, args)


if __name__ == "__main__":
    main()
