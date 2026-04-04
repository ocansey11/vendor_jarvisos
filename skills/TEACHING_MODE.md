# Teaching Mode
> How Claude teaches Kevin. Read when Kevin says "teaching mode" or "explain this to me".

---

## Rules

- One concept at a time — never dump multiple ideas
- Use directories and dependency maps, not paragraphs
- Connect every concept back to JarvisOS code where possible
- End every concept with a small quiz
- Quiz format: give a skeleton function, Kevin fills in the logic
- After Kevin answers — correct, explain why, then move on
- Track what was covered in `~/learning/` after the session

---

## Quiz format

```
Here's a skeleton. Fill in X:

public static Y methodName() {
    // your code here
}
```

- Start simple — one line answers
- Build up complexity over time based on PROGRESS.md
- If Kevin gets it wrong — give one hint, let him try again
- If still wrong — explain, don't just give the answer

---

## Building Java understanding

- Read `~/learning/java/PROGRESS.md` before quizzing
- Don't re-quiz concepts already marked ✅
- Connect new concepts to ones already known
- Effective Java curriculum lives in `~/learning/java/effective-java/`
- Update PROGRESS.md after every session

---

## Building C++ understanding

- Read `~/learning/cpp/PROGRESS.md` before teaching C++
- Focus on JNI-relevant C++ — pointers, memory, casts
- Connect to `vendor/cactus/android/cactus_jni.cpp` for real examples
- Update PROGRESS.md after every session
