"""The local Gemma 4 26b that build-time text steps run through Ollama, and the quote check they share."""

import json
import urllib.request

BASE_URL = "http://localhost:11434"
MODEL = "gemma4:26b"
# Gemma 4's recommended sampler. Ollama's default context is small and silently cuts a longer prompt, so the run
# sets it and checks the prompt fit.
SAMPLER = {"temperature": 1.0, "top_p": 0.95, "top_k": 64, "seed": 1}
NUM_CTX = 8192


def chat(system: str, shots: tuple[tuple[str, str], ...], schema: dict, user: str, max_new_tokens: int) -> dict:
    """One Ollama chat turn after worked (user, reply) examples; raises on a reply that is cut short or empty."""
    messages = [{"role": "system", "content": system}]
    for asked, answered in shots:
        messages += [{"role": "user", "content": asked}, {"role": "assistant", "content": answered}]
    messages.append({"role": "user", "content": user})
    body = {
        "model": MODEL,
        "messages": messages,
        "stream": False,
        # Gemma 4 thinks by default and the thinking spent the whole reply budget on Oct 9; these are quote lookups.
        "think": False,
        "format": schema,
        "options": {**SAMPLER, "num_ctx": NUM_CTX, "num_predict": max_new_tokens},
    }
    request = urllib.request.Request(
        f"{BASE_URL}/api/chat", json.dumps(body).encode(), {"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=900) as response:
        reply = json.load(response)
    if reply.get("done_reason") != "stop" or not reply["message"]["content"].strip():
        raise ValueError(f"{MODEL}: reply ended {reply.get('done_reason')!r} with no usable content")
    return {"text": reply["message"]["content"], "cut_off": reply.get("prompt_eval_count", 0) >= NUM_CTX - 8}


def digest() -> str:
    """The first 12 hex digits of the local MODEL's digest, as `ollama list` shows it; raises when it isn't pulled."""
    with urllib.request.urlopen(f"{BASE_URL}/api/tags", timeout=30) as response:
        models = json.load(response)["models"]
    for model in models:
        if model["name"] == MODEL:
            return model["digest"][:12]
    raise ValueError(f"{MODEL} is not pulled in the local Ollama")


def squash(text: str) -> str:
    """Whitespace-collapsed, case-folded text, so a quote matches however the article wrapped its lines."""
    return " ".join(text.split()).casefold()
