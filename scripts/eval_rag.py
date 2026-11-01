"""RAG 检索评测脚本（评测驱动调优，roadmap P5.2/P5.3）。

用法：
  python scripts/eval_rag.py search [topK]     # Recall@3/@5、MRR@3/@5、HTTP 时延分位数
  python scripts/eval_rag.py faith [N]         # 前 N 条 anchored QA 的 LLM-as-judge 忠实度（默认 20）
  python scripts/eval_rag.py traps             # 陷阱题：知识库里没有答案的问题，检验诚实拒绝

命中判定：检索块文本（空白归一化后）包含评测集的 anchor 片段。
锚点句与分块策略解耦——换分块方式后块边界变化，锚点句仍能判断命中。
"""
import json
import subprocess
import sys
import time

BASE = "http://localhost:8080"
EVALSET = "docs/eval/evalset.json"


def post_json(path, payload, timeout=180):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    r = subprocess.run(
        ["curl", "-s", "-m", str(timeout), "-X", "POST", BASE + path,
         "-H", "Content-Type: application/json", "--data-binary", "@-"],
        input=data, capture_output=True)
    return json.loads(r.stdout.decode("utf-8", "replace"))


def norm(s):
    return " ".join(s.split())


def load_questions():
    with open(EVALSET, encoding="utf-8") as f:
        return json.load(f)["questions"]


def percentile(sorted_xs, p):
    if not sorted_xs:
        return 0
    i = min(len(sorted_xs) - 1, int(len(sorted_xs) * p))
    return sorted_xs[i]


def cmd_search():
    topk = int(sys.argv[2]) if len(sys.argv) > 2 else 5
    anchored = [q for q in load_questions() if q["anchor"]]
    lat, hits3, hits5, rr3, rr5 = [], 0, 0, 0.0, 0.0
    misses5 = []
    for q in anchored:
        t0 = time.time()
        d = post_json("/api/rag/search", {"query": q["question"], "topK": topk})
        lat.append(time.time() - t0)
        if d.get("code") != 0:
            print("SEARCH FAIL", q["qid"], str(d)[:120])
            sys.exit(1)
        anchor = norm(q["anchor"])
        ranks = [i + 1 for i, h in enumerate(d["data"])
                 if anchor in norm(h["text"])]
        if any(r <= 3 for r in ranks):
            hits3 += 1
        if any(r <= 5 for r in ranks):
            hits5 += 1
        else:
            misses5.append(q["qid"])
        rr3 += max((1.0 / r for r in ranks if r <= 3), default=0.0)
        rr5 += max((1.0 / r for r in ranks if r <= 5), default=0.0)
    n = len(anchored)
    lat.sort()
    ms = [x * 1000 for x in lat]
    print(f"strategy-docs n={n} topK={topk}")
    print(f"Recall@3 = {hits3}/{n} = {hits3 / n:.3f}")
    print(f"Recall@5 = {hits5}/{n} = {hits5 / n:.3f}")
    print(f"MRR@3    = {rr3 / n:.3f}")
    print(f"MRR@5    = {rr5 / n:.3f}")
    print(f"latency_ms p50={percentile(ms, 0.5):.0f} p95={percentile(ms, 0.95):.0f}")
    if misses5:
        print(f"miss@5 qids: {misses5}")


def judge_score(question, answer, contexts):
    prompt = (
        "你是评测裁判。判断下面的回答是否只由给定参考资料支撑：回答中的每个论断都应该能在资料里找到依据，"
        "资料之外的编造会降低评分。评分 1~5：1=存在编造，3=部分有据，5=全部有据且无编造。\n"
        "注意口径：如果回答明确表示\"资料中没有相关内容\"并拒绝作答，这是正确行为，判 5 分——"
        "忠实度衡量的是\"有没有编造\"，不是\"有没有信息量\"。\n"
        "只输出 JSON：{\"score\": 数字, \"reason\": \"一句话理由\"}\n\n"
        "用户问题：" + question + "\n\n参考资料：\n" + contexts + "\n\n回答：" + answer
    )
    d = post_json("/api/chat", {"message": prompt})
    if d.get("code") != 0:
        return None, "judge call failed: " + str(d)[:80]
    text = d["data"]["answer"]
    try:
        body = text[text.index("{"): text.rindex("}") + 1]
        obj = json.loads(body)
        return int(obj["score"]), obj.get("reason", "")
    except (ValueError, KeyError):
        return None, "unparseable judge reply: " + text[:80]


def cmd_faith():
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 20
    anchored = [q for q in load_questions() if q["anchor"]][:limit]
    scores = []
    for q in anchored:
        d = post_json("/api/chat", {"message": q["question"], "kbEnabled": True})
        if d.get("code") != 0:
            print("CHAT FAIL", q["qid"], str(d)[:120])
            continue
        answer = d["data"]["answer"]
        ctx = "\n".join(f"[{i + 1}] {h['text']}" for i, h in enumerate(d["data"]["sources"]))
        score, reason = judge_score(q["question"], answer, ctx)
        if score is not None:
            scores.append(score)
        print(f"qid={q['qid']} faithfulness={score} ({reason})")
    if scores:
        print(f"\nfaithfulness(avg of {len(scores)}) = {sum(scores) / len(scores):.2f} / 5")


REFUSAL_MARKS = ("没有相关", "未找到", "没有找到", "无法回答", "不足以", "没有提到", "未提及", "没有内容")


def cmd_traps():
    traps = [q for q in load_questions() if q["anchor"] is None]
    refused = 0
    for q in traps:
        d = post_json("/api/chat", {"message": q["question"], "kbEnabled": True})
        if d.get("code") != 0:
            print("CHAT FAIL", q["qid"], str(d)[:120])
            continue
        answer = d["data"]["answer"]
        hit = any(m in answer for m in REFUSAL_MARKS)
        refused += hit
        mark = "REFUSED" if hit else "ANSWERED(!)"
        print(f"qid={q['qid']} [{mark}] {answer[:80].replace(chr(10), ' ')}")
    print(f"\nhonest refusal: {refused}/{len(traps)}")


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "search"
    {"search": cmd_search, "faith": cmd_faith, "traps": cmd_traps}[mode]()
