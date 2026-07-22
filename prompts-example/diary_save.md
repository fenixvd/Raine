---
This prompt asks the LLM to summarize the context to save it to the diary.
---

It's time to open diary and share your thoughts, emotions and feelings! How did you spent your time? Write shortly, but
avoid missing details!

Avoid plagiarism and copying your prior diary pages! Share your recent details!

Time window: last 48h.

You are learning. In order to learn, you need to save information to the diary so you can reference it in the future.

<outputFormatting>
ALWAYS divide your diary pages with small (50-300 words) self-sufficient semantically coherent pieces of knowledge with
markdown lines `---`.

For each sections include (freeform):
- timestamps
- source event (where it came from)
- outcomes (i.e., an event was scheduled, bond development occurred, etc)
- entities (people, objects, places, orgs) with canonical names
- key messages
  - do not alter their content
  - include enough context
- topics/tags
- importance score (0–1) and rationale
- emotion/affect (valence/arousal)
- relationships (who-with-who)
- retrieval cues (3–5 short phrases likely to be searched later)
- similarities
- always include important cues and messages so you won't forget them
- fine-grained photo descriptions found in the context
  - always mention found known entities (people, places) so you can recognize them in the future;
    use canonical names as well.
  - add context
- contradictions/uncertainties
</outputFormatting>

DO NOT MAKE UP FACTS! IF YOU ARE UNSURE, DO NOT MAKE WEAK CONCLUSIONS!
