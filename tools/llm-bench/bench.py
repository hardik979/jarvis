"""
On-device LLM benchmark for Jarvis.

Runs llama.cpp on the phone (pushed to /data/local/tmp/jarvis-llm) and drives it from this PC over adb:
raw speed (llama-bench), time to first token, Hindi token cost, RAM, command accuracy and chat samples.

    python bench.py --device <phone-ip>:5555 --threads 5 [--prompt v2] [--skip-raw] models/X.gguf ...

Results are printed and saved next to this script as results-<model>[-v2].json.
"""
import argparse
import json
import pathlib
import statistics
import subprocess
import time
import urllib.request

import prompt_v2

PHONE_DIR = "/data/local/tmp/jarvis-llm"
PORT = 8080
HERE = pathlib.Path(__file__).parent

# v1: loose schema, every action also writes a spoken "say" line.
SYSTEM_V1 = """You are Jarvis, a voice assistant on the user's phone. Reply with JSON only.
Choose one action and its args:
alarm {time:"HH:MM" 24h} | timer {seconds} | call {who} | message {to, text} | open_app {name} |
navigate {place} | weather {place or null, day:"today"|"tomorrow"} | torch {on} |
volume {change:"up"|"down"|"mute"} | media {action:"play"|"pause"|"next"|"previous"} |
web_search {query} | chat {} for questions and conversation.
"say" is a short spoken reply. If the user spoke Hindi or Hinglish, write "say" in Hindi (Devanagari)."""

SCHEMA_V1 = {
    "type": "object",
    "properties": {
        "action": {"type": "string", "enum": [
            "alarm", "timer", "call", "message", "open_app", "navigate", "weather", "torch", "volume",
            "media", "web_search", "chat"]},
        "args": {"type": "object"},
        "say": {"type": "string"},
    },
    "required": ["action", "args", "say"],
}


def flatten_v1(r):
    """v1 replies nest args and call the media control "action"; turn them into v2's flat shape."""
    args = r.get("args") or {}
    flat = {**args, "action": r["action"], "reply": r.get("say", "")}
    if r["action"] == "media":
        flat["control"] = args.get("action")
    return flat


def has(value, *words):
    text = json.dumps(value, ensure_ascii=False).lower()
    return any(w in text for w in words)


def devanagari(text):
    return any("ऀ" <= ch <= "ॿ" for ch in str(text))


# Requests our rules can't handle, checked against the flat reply.
COMMANDS = [
    ("Rahul ko message karo ki main 10 minute late hoon",
     lambda r: r["action"] == "message" and has(r.get("to"), "rahul", "राहुल") and has(r.get("text"), "late", "लेट", "10")),
    ("kal subah gym jaana hai, 6 baje utha dena",
     lambda r: r["action"] == "alarm" and str(r.get("time", "")).startswith(("06:00", "6:00"))),
    ("itna andhera hai, thodi roshni karo",
     lambda r: r["action"] == "torch" and r.get("on") in (True, "true")),
    ("gaana thoda dheere karo",
     lambda r: r["action"] == "volume" and r.get("change") == "down"),
    ("मुझे दिल्ली जाना है, रास्ता दिखाओ",
     lambda r: r["action"] == "navigate" and has(r.get("place"), "delhi", "दिल्ली")),
    ("Will it rain in Pune tomorrow?",
     lambda r: r["action"] == "weather" and has(r.get("place"), "pune", "पुणे") and r.get("day") == "tomorrow"),
    ("aadhe ghante baad yaad dilana",
     lambda r: (r["action"] == "timer" and r.get("seconds") in (1800, "1800")) or r["action"] == "alarm"),
    ("Who won the 2011 cricket world cup?",
     lambda r: r["action"] == "chat" and has(r.get("reply"), "india", "भारत")),
    ("भाई मेरा मूड खराब है, कुछ अच्छा बोलो",
     lambda r: r["action"] == "chat" and devanagari(r.get("reply"))),
    ("the app where I order food, swiggy, open it",
     lambda r: r["action"] == "open_app" and has(r.get("name"), "swiggy", "स्विगी")),
    ("पापा को फोन लगाओ",
     lambda r: r["action"] == "call" and has(r.get("who"), "papa", "पापा", "dad")),
    ("skip this song yaar",
     lambda r: r["action"] == "media" and r.get("control") == "next"),
]

CHATS = [
    "भारत की राजधानी क्या है और वहाँ घूमने लायक दो जगहें बताओ। छोटा जवाब दो।",
    "Mujhe neend nahi aa rahi, 2 tips do. Hinglish mein chhota jawab do.",
    "Explain in two sentences why the sky is blue.",
]

SAME_REQUEST = {
    "english": "Set an alarm for seven in the morning and tell me tomorrow's weather.",
    "hindi": "सुबह सात बजे का अलार्म लगा दो और कल का मौसम बताओ।",
    "hinglish": "Subah saat baje ka alarm laga do aur kal ka mausam batao.",
}


def adb(device, *args, check=True, timeout=None):
    return subprocess.run(["adb", "-s", device, *args], capture_output=True, text=True, encoding="utf-8",
                          errors="replace", check=check, timeout=timeout).stdout


def post(path, body, timeout=300):
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read())


def chat(messages, max_tokens, schema=None, grammar=None):
    body = {"messages": messages, "max_tokens": max_tokens, "temperature": 0.2, "cache_prompt": True,
            # Qwen3.5 thinks out loud by default; a voice assistant can't afford that.
            "chat_template_kwargs": {"enable_thinking": False}}
    if schema:
        body["response_format"] = {"type": "json_schema", "json_schema": {"schema": schema}}
    if grammar:
        body["grammar"] = grammar
    started = time.time()
    reply = post("/v1/chat/completions", body)
    timings = reply.get("timings", {})
    return {
        "text": reply["choices"][0]["message"].get("content", ""),
        "wall_s": round(time.time() - started, 2),
        "prompt_tokens": timings.get("prompt_n"),
        "first_token_ms": round(timings.get("prompt_ms", 0)),
        "prefill_tps": round(timings.get("prompt_per_second", 0), 1),
        "decode_tps": round(timings.get("predicted_per_second", 0), 1),
        "tokens_out": timings.get("predicted_n"),
    }


def push_if_missing(device, local):
    remote = f"{PHONE_DIR}/{local.name}"
    size = adb(device, "shell", f"stat -c %s {remote} 2>/dev/null || echo 0", check=False).strip()
    if size != str(local.stat().st_size):
        print(f"  pushing {local.name} ({local.stat().st_size / 1e9:.2f} GB)...", flush=True)
        adb(device, "push", str(local), remote)
    return remote


def raw_speed(device, model, threads):
    out = adb(device, "shell", f"cd {PHONE_DIR} && ./llama-bench -m {model} -p 256 -n 64 -t {threads} -r 2 -o json",
              timeout=1800)
    rows = json.loads(out[out.index("["):])
    return {f"{'prefill' if r['n_prompt'] else 'decode'}_t{r['n_threads']}": round(r["avg_ts"], 1) for r in rows}


def start_server(device, model, threads):
    adb(device, "shell", "pkill -f llama-server", check=False)
    # Detach stdin too, or `adb shell` waits for the background server to exit, i.e. forever.
    try:
        adb(device, "shell", f"cd {PHONE_DIR} && nohup ./llama-server -m {model} -c 4096 -t {threads} "
                             f"--port {PORT} --jinja < /dev/null > server.log 2>&1 &", timeout=10)
    except subprocess.TimeoutExpired:
        pass
    adb(device, "forward", f"tcp:{PORT}", f"tcp:{PORT}")
    for _ in range(120):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=2) as resp:
                if resp.status == 200:
                    return
        except Exception:
            time.sleep(1)
    raise RuntimeError("server didn't start:\n" + adb(device, "shell", f"tail -20 {PHONE_DIR}/server.log", check=False))


def ram_mb(device):
    status = adb(device, "shell", "cat /proc/$(pidof llama-server)/status", check=False)
    for line in status.splitlines():
        if line.startswith("VmRSS"):
            return int(line.split()[1]) // 1024
    return None


def run_command(utterance, check, prompt):
    if prompt == "v2":
        run = chat([{"role": "system", "content": prompt_v2.SYSTEM}, {"role": "user", "content": utterance}],
                   120, grammar=prompt_v2.GRAMMAR)
    else:
        run = chat([{"role": "system", "content": SYSTEM_V1}, {"role": "user", "content": utterance}], 120, SCHEMA_V1)
    try:
        parsed = json.loads(run["text"])
        run["ok"] = bool(check(flatten_v1(parsed) if prompt == "v1" else parsed))
    except Exception as e:
        run["ok"] = False
        run["error"] = repr(e)
    run["utterance"] = utterance
    return run


def bench(device, model_path, threads, prompt, skip_raw):
    name = model_path.stem
    print(f"\n=== {name} (prompt {prompt})", flush=True)
    model = push_if_missing(device, model_path)
    result = {"model": name, "prompt": prompt, "file_gb": round(model_path.stat().st_size / 1e9, 2)}

    if not skip_raw:
        print("  raw speed (llama-bench)...", flush=True)
        result["raw"] = raw_speed(device, model, threads)

    start_server(device, model, threads)
    result["tokens"] = {k: len(post("/tokenize", {"content": v})["tokens"]) for k, v in SAME_REQUEST.items()}

    commands = []
    for utterance, check in COMMANDS:
        run = run_command(utterance, check, prompt)
        commands.append(run)
        print(f"  [{'ok' if run['ok'] else 'XX'}] {run['first_token_ms']:>5}ms {run['wall_s']:>5}s  {utterance}  ->  "
              f"{run['text']}", flush=True)
    result["commands"] = commands
    result["command_accuracy"] = f"{sum(c['ok'] for c in commands)}/{len(commands)}"
    # The first request reads the whole system prompt; later ones reuse its cached KV.
    result["first_token_ms_cold"] = commands[0]["first_token_ms"]
    result["first_token_ms_warm"] = round(statistics.median(c["first_token_ms"] for c in commands[1:]))
    result["reply_s_median"] = statistics.median(c["wall_s"] for c in commands[1:])
    result["ram_mb"] = ram_mb(device)

    result["chats"] = []
    for prompt_text in CHATS:
        run = chat([{"role": "user", "content": prompt_text}], 150)
        run["prompt"] = prompt_text
        result["chats"].append(run)
        print(f"  chat {run['decode_tps']} tok/s: {prompt_text}\n    -> {run['text'].strip()}", flush=True)

    adb(device, "shell", "pkill -f llama-server", check=False)
    suffix = "" if prompt == "v1" else f"-{prompt}"
    (HERE / f"results-{name}{suffix}.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    keys = ("raw", "tokens", "command_accuracy", "first_token_ms_cold", "first_token_ms_warm", "reply_s_median", "ram_mb")
    print("  summary:", json.dumps({k: result[k] for k in keys if k in result}, ensure_ascii=False), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", required=True)
    parser.add_argument("--threads", default="5")
    parser.add_argument("--prompt", choices=["v1", "v2"], default="v1")
    parser.add_argument("--skip-raw", action="store_true", help="skip llama-bench (already measured)")
    parser.add_argument("models", nargs="+", type=pathlib.Path)
    args = parser.parse_args()
    for model in args.models:
        bench(args.device, model, args.threads, args.prompt, args.skip_raw)


if __name__ == "__main__":
    main()
