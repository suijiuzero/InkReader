"""Batch file-selection acceptance. Explicitly targets the disposable emulator only."""
import importlib.util
import json
import re
import subprocess
import sys
import time
from pathlib import Path

stage = sys.argv[1]
sys.argv = ["emulator_txt_ui.py", "noop"]
spec = importlib.util.spec_from_file_location("ui", Path(__file__).with_name("emulator_txt_ui.py"))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)
ui.ROOT = Path(__file__).resolve().parents[2] / "app/build/audit/file-batch-2026-10-24"
ui.ROOT.mkdir(parents=True, exist_ok=True)


def wait_for(text, timeout=35):
    until = time.monotonic() + timeout
    while True:
        try:
            root, parents = ui.dump()
        except RuntimeError:
            if time.monotonic() >= until: raise
            time.sleep(.5)
            continue
        if any(text in n.get("text", "") or n.get("content-desc") == text for n in root.iter("node")):
            return root, parents
        assert time.monotonic() < until, "Missing: " + text


def has(text):
    root, _ = ui.dump()
    return any(n.get("text") == text or n.get("content-desc") == text for n in root.iter("node"))


def shot(name):
    (ui.ROOT / name).write_bytes(ui.adb("exec-out", "screencap", "-p"))


def record(check):
    path = ui.ROOT / "results.json"
    results = json.loads(path.read_text()) if path.exists() else []
    results.append(dict(check=check, status="PASS"))
    path.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print("PASS", check, flush=True)


def badge(label, index):
    root, parents = wait_for(label)
    n = next(n for n in root.iter("node") if n.get("text") == label)
    while n.get("clickable") != "true" and n in parents:
        n = parents[n]
    texts = [c.get("text") for c in n.iter("node")]
    assert (str(index) in texts) if index else not any(t and t.isdigit() for t in texts), (label, index, texts)


def endpoint(label):
    root, parents = ui.dump()
    for n in root.iter("node"):
        if n.get("text") != label: continue
        row = n
        while row.get("clickable") != "true" and row in parents: row = parents[row]
        if row.get("clickable") != "true": continue
        x1,y1,x2,y2 = map(int,re.findall(r"\d+",row.get("bounds")))
        ui.adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2))
        return
    raise AssertionError("Missing endpoint: " + label)


def menu(anchor="part-c.txt", action=None):
    ui.click(anchor, long=True)
    wait_for("Batch selection")
    if action:
        reveal(action)
        ui.click(action)


def reveal(text, up=True, innermost=True):
    for _ in range(5):
        if has(text):
            return
        root,_=ui.dump()
        regions=[]
        for n in root.iter("node"):
            if n.get("scrollable") != "true": continue
            x1,y1,x2,y2=map(int,re.findall(r"\d+",n.get("bounds")))
            regions.append(((x2-x1)*(y2-y1),x1,y1,x2,y2))
        assert regions, "No scrollable region for " + text
        _,x1,y1,x2,y2=min(regions) if innermost else max(regions)
        ui.adb("shell", "input", "swipe", str((x1+x2)//2), str(y2-30 if up else y1+30),
               str((x1+x2)//2), str(y1+30 if up else y2-30), "350")
    raise AssertionError("Cannot reveal: " + text)


def verify(order):
    wait_for("Selected: " + str(len(order)))
    for i, name in enumerate(order, 1):
        badge(name, i)


if stage == "seed":
    ui.adb("shell", "mkdir", "-p", "/sdcard/Download/INK_PICKER_UI/batch/folder-middle")
    for letter in "abcde":
        name = "part-" + letter + ".txt"
        (ui.ROOT / name).write_text(("BATCH_" + letter.upper() + " A plain story without chapter headings. ") * 35)
        subprocess.run(ui.ADB + ["push", name, "/sdcard/Download/INK_PICKER_UI/batch/" + name], cwd=ui.ROOT,
                       check=True, capture_output=True, timeout=45)
    print("Seeded five plain test files and one subfolder", flush=True)
elif stage in ("modes", "modes-resume"):
    if stage == "modes":
        ui.click("Import"); wait_for("Folder home"); ui.click("INK_PICKER_UI"); wait_for("zeta.txt")
        ui.click("zeta.txt"); ui.click("batch"); wait_for("part-c.txt")
        ui.click("folder-middle", long=True); wait_for("part-c.txt"); wait_for("Selected: 1")
        assert not has("Batch selection")
        record("directory hold is inert and does not navigate or select")
    else:
        ui.click("Cancel"); wait_for("Selected: 1")
    menu(); shot("batch-menu.png"); ui.click("Cancel"); wait_for("Selected: 1"); badge("part-c.txt", 0)
    record("file hold opens menu without toggling file; cancel leaves selection unchanged")
    menu(action="Select from here to end"); wait_for("Selected: 4")
    badge("part-c.txt", 2); badge("part-d.txt", 3); badge("part-e.txt", 4)
    record("forward from anchor appends c,d,e after cross-directory selection")
    menu(action="Select from here to start"); wait_for("Selected: 6")
    for name, pos in [("part-c.txt", 2),("part-d.txt", 3),("part-e.txt", 4),("part-b.txt", 5),("part-a.txt", 6)]: badge(name,pos)
    menu(action="Select all in reverse order"); wait_for("Selected: 6")
    badge("part-b.txt", 5); badge("part-a.txt", 6)
    record("reverse from anchor skips existing files and appends b,a; repeated batch is a no-op")
    ui.click("Clear"); menu(action="Select all in display order")
    verify(["part-"+x+".txt" for x in "abcde"]); shot("all-forward.png")
    ui.click("Clear"); menu(action="Select all in reverse order")
    verify(["part-"+x+".txt" for x in "edcba"]); shot("all-reverse.png")
    record("all forward and reverse exclude subfolder and produce contiguous import badges")
elif stage in ("ranges", "ranges-resume"):
    if stage == "ranges":
        ui.click("Clear"); menu(action="Select a range between two files"); wait_for("Choose ending file")
        shot("range-endpoints.png"); ui.click("part-a.txt"); verify(["part-"+x+".txt" for x in "cba"])
        record("two endpoint range includes both ends and follows reverse direction c,b,a")
        ui.click("Clear"); menu("part-b.txt", "Select a range between two files")
    reveal("part-e.txt")
    ui.click("part-e.txt"); verify(["part-"+x+".txt" for x in "bcde"])
    record("forward range b,c,d,e includes both endpoints and endpoint list scrolls")
    ui.click("Clear"); menu(action="Select a range between two files"); ui.click("Back to options"); wait_for("Batch selection")
    ui.click("Cancel"); wait_for("Selected: 0")
    menu(action="Select a range between two files"); endpoint("part-c.txt"); verify(["part-c.txt"])
    record("range back and cancel do not select; same endpoint selects only one file")
elif stage == "filter":
    ui.click("Clear"); ui.click("Names ascending; tap for descending")
    menu(action="Select from here to end"); verify(["part-"+x+".txt" for x in "cba"])
    ui.click("Clear"); menu(action="Select from here to start"); verify(["part-"+x+".txt" for x in "cde"])
    record("anchor directions follow current descending display order")
    ui.click("Clear"); ui.click("Search this folder"); ui.adb("shell","input","text","part-c")
    ui.adb("shell","input","keyevent","4"); menu(action="Select all in display order")
    verify(["part-c.txt"]); ui.click("Clear search"); badge("part-a.txt",0); badge("part-e.txt",0)
    record("search-filtered bulk selection never selects hidden files")
    ui.click("Clear"); ui.click("Names descending; tap for ascending")
    menu("part-d.txt","Select a range between two files"); wait_for("Choose ending file")
    ui.adb("shell","input","keyevent","3"); time.sleep(1); ui.adb("shell","am","kill","com.inkreader"); time.sleep(.5)
    assert not subprocess.run(ui.ADB+["shell","pidof","com.inkreader"],capture_output=True).stdout.strip()
    ui.adb("shell","am","start","-n","com.inkreader/.MainActivity"); wait_for("Choose ending file")
    ui.click("part-b.txt"); verify(["part-"+x+".txt" for x in "dcb"]); shot("restored-range.png")
    record("range dialog and immutable file snapshot survive background process recreation")
    ui.click("*"); wait_for("Folder home"); ui.click("INK_PICKER_UI",long=True); wait_for("Folders selected: 1")
    ui.click("Cancel"); wait_for("Selected: 3"); ui.click("INK_PICKER_UI"); ui.click("batch"); verify(["part-"+x+".txt" for x in "dcb"])
    record("folder-home hold multi-remove and cross-folder selected order remain intact")
    ui.click("Continue import"); wait_for("Import 3 files")
    root,_=ui.dump(); assert any(n.get("text")=="part-d.txt, part-c.txt, part-b.txt" for n in root.iter("node"))
    shot("import-order.png"); record("batch queue transfers to import confirmation in exact d,c,b order")
elif stage in ("import", "import-resume", "verify-import"):
    if stage != "verify-import":
        if stage == "import":
            wait_for("Import 3 files"); ui.click("Merge into one book"); wait_for("Leave blank to auto-generate")
            ui.click("Leave blank to auto-generate"); ui.adb("shell","input","text","INK_BATCH_UI"); ui.adb("shell","input","keyevent","4")
            reveal("One chapter per file", innermost=False)
            # Keep the radio row clear of the fixed confirmation buttons.
            ui.adb("shell","input","swipe","540","1650","540","1100","350")
        ui.click("One chapter per file")
        reveal("1. part-d", up=False, innermost=False)
        root,_=ui.dump(); texts=[n.get("text") for n in root.iter("node")]
        assert all(t in texts for t in ["1. part-d","2. part-c","3. part-b"]), texts
        shot("import-preview.png"); ui.click("Start import")
    wait_for("397 books")
    import sqlite3
    for name in ["ink_reader.db","ink_reader.db-wal","ink_reader.db-shm"]:
        try: (ui.ROOT / name).write_bytes(ui.adb("exec-out","run-as","com.inkreader","cat","databases/"+name))
        except subprocess.CalledProcessError: pass
    db=sqlite3.connect(str(ui.ROOT / "ink_reader.db"))
    book=db.execute("SELECT id FROM books WHERE title=?",("INK_BATCH_UI",)).fetchall()
    assert len(book)==1, book
    chapters=db.execute("SELECT idx,title,file FROM chapters WHERE book_id=? ORDER BY idx",(book[0][0],)).fetchall()
    assert [row[1] for row in chapters]==["part-d","part-c","part-b"], chapters
    evidence=[]
    for (idx,title,file), letter in zip(chapters,"dcb"):
        body=ui.adb("exec-out","run-as","com.inkreader","cat","files/chapters/"+file).decode()
        assert body.count("BATCH_"+letter.upper())==35
        evidence.append(dict(index=idx,title=title,bodyMarkerCount=35))
    (ui.ROOT / "imported-data.json").write_text(json.dumps(evidence,indent=2))
    db.close(); record("preview and real imported chapter order d,c,b agree; all 35 body markers in each file preserved")
    ui.click("Search books"); ui.adb("shell","input","text","INK_BATCH_UI"); ui.adb("shell","input","keyevent","4")
    wait_for("3 chapters"); ui.click("3 chapters",long=True); wait_for("Chapter Management"); ui.click("More")
    ui.click("Delete whole book"); wait_for("'INK_BATCH_UI'"); ui.click("Delete"); wait_for("396 books")
    for letter in "abcde": ui.adb("shell","test","-f","/sdcard/Download/INK_PICKER_UI/batch/part-"+letter+".txt")
    pid=ui.adb("shell","pidof","com.inkreader").decode().strip().split()[0]
    assert "FATAL EXCEPTION" not in ui.adb("logcat","-b","crash","-d","--pid="+pid).decode(errors="replace")
    record("temporary test book removed; original 396 books and all five source files preserved; no current fatal exception")
elif stage == "final-smoke":
    wait_for("396 books"); ui.click("Import"); wait_for("Folder home"); ui.click("INK_PICKER_UI"); ui.click("batch")
    menu("part-e.txt"); wait_for("1 file in range"); shot("final-singular.png"); ui.click("Cancel"); wait_for("Selected: 0")
    rotation = ui.adb("shell","settings","get","system","user_rotation").decode().strip()
    automatic = ui.adb("shell","settings","get","system","accelerometer_rotation").decode().strip()
    try:
        menu()
        ui.adb("shell","settings","put","system","accelerometer_rotation","0")
        ui.adb("shell","settings","put","system","user_rotation","1")
        wait_for("Batch selection"); shot("landscape-menu.png")
        reveal("Select a range between two files"); ui.click("Select a range between two files")
        wait_for("Choose ending file"); wait_for("Selects from the starting file above"); shot("landscape-range.png"); ui.click("Cancel"); wait_for("Selected: 0")
    finally:
        for setting,value in [("user_rotation",rotation),("accelerometer_rotation",automatic)]:
            ui.adb("shell","settings","delete" if value=="null" else "put","system",setting,*([] if value=="null" else [value]))
    ui.click("Back"); ui.click("Back"); ui.click("Back"); wait_for("396 books")
    record("corrected final APK opens range at top in landscape; singular text, cancel and original shelf remain correct")
elif stage == "dump":
    root,_=ui.dump()
    for n in root.iter("node"):
        if n.get("text") or n.get("content-desc"): print(n.get("text"),"|",n.get("content-desc"),"|",n.get("bounds"))
