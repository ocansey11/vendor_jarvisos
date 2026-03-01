# JarvisOS Memory Schema
> ObjectBox schema for the JarvisOS "Brain" — what JarvisOS owns vs what Cactus owns.
> Last updated: March 2026

---

## Division of Responsibility

| Layer | Owner | What it does |
|-------|-------|-------------|
| Model inference + quantization | Cactus | INT4/INT8/FP16 weight quantization, graph execution |
| Embedding generation | Cactus (cactus_embed) | Text to float vector |
| Vector index + RAG query | Cactus (cactus_index_*, cactus_rag_query) | Custom binary index (index.bin/data.bin), NOT ObjectBox |
| File metadata + pointers | JarvisOS | Where files live, what they are |
| Conversation history | JarvisOS | What the user said and when |
| User context / profile | JarvisOS | Preferences, habits, access patterns |
| Folder breadcrumbs | JarvisOS | Lightweight per-directory index metadata |
| Retrieval strategy | JarvisOS | Metadata-first, embed on demand |

NOTE: Cactus does NOT use ObjectBox. Its index is a custom C++ binary store (index.bin + data.bin).
JarvisOS ObjectBox and Cactus index are completely separate stores.

---

## Retrieval Philosophy

Do NOT embed everything upfront. This duplicates data and kills mobile storage and battery.

Two-stage approach:
1. Stage 1 - Metadata retrieval (free): BM25/keyword + folder structure + tags. No embeddings. Narrows candidates from thousands to tens.
2. Stage 2 - Semantic retrieval (on demand): Call cactus_embed() + cactus_index_query() only on the shortlist from Stage 1.

Indexing happens via trickle indexing — 10 items at a time, on charge/idle only, via Android WorkManager.

---

## ObjectBox Schema

### Entity 1: SourceFile
Represents any file on the device JarvisOS is aware of.
No content stored — just a pointer and metadata.
JarvisOS never renames files without user permission. Aliases and tags are stored here instead.

    @Entity
    public class SourceFile {
        @Id long id;
        String filePath;
        String fileName;
        String userAlias;         // what the user calls this file e.g. "my biology notes"
        String[] tags;            // user or assistant assigned tags e.g. ["university", "2024"]
        String mimeType;
        long fileSizeBytes;
        String fileHash;          // SHA-256 to detect changes, avoid re-embedding
        boolean isIndexed;
        int cactusIndexId;        // ID in Cactus's custom binary index — NOT ObjectBox
        long createdAt;
        long lastModifiedAt;
        long lastAccessedAt;
        ToOne<Folder> folder;
        ToMany<DocumentChunk> chunks;
        ToMany<Conversation> conversations;
    }

### Entity 2: DocumentChunk
A chunk of a SourceFile. Stores a compressed summary, NOT full content.
Full content stays in the original file. Cactus holds the embedding.

    @Entity
    public class DocumentChunk {
        @Id long id;
        int cactusIndexId;        // pointer to Cactus binary index entry
        int chunkIndex;
        String summary;           // 2-3 sentence compressed summary
        int tokenCount;
        long embeddedAt;
        boolean embeddingRetained; // false = Cactus index entry deleted after task completed
        ToOne<SourceFile> sourceFile;
    }

### Entity 3: Folder
Per-directory metadata — the breadcrumb layer.
Each folder gets a lightweight summary of contents. No embeddings.

    @Entity
    public class Folder {
        @Id long id;
        String folderPath;
        String displayName;
        String[] tags;            // user or assistant assigned tags
        String summary;           // e.g. "Contains Kevin's university notes from 2024"
        int fileCount;
        long lastUpdatedAt;
        ToMany<SourceFile> files;
        ToOne<Folder> parentFolder;
        ToMany<Folder> subFolders;
    }

### Entity 4: Conversation

    @Entity
    public class Conversation {
        @Id long id;
        String title;
        long createdAt;
        long lastMessageAt;
        String lastMessagePreview;
        String mode;              // text or voice
        ToMany<Message> messages;
        ToMany<SourceFile> referencedFiles;
    }

### Entity 5: Message

    @Entity
    public class Message {
        @Id long id;
        String content;
        boolean isUser;
        long timestamp;
        String source;            // text, voice, tool
        ToOne<Conversation> conversation;
    }

### Entity 6: UserContext
The brain — persistent facts JarvisOS learns about the user.
Single record, updated over time.

    @Entity
    public class UserContext {
        @Id long id;
        String preferredName;
        String[] interests;
        String[] frequentFolders;
        long lastActiveAt;
        String deviceLocale;
    }

### Entity 7: AccessLog
Lightweight log of file/folder access per conversation.
Used to improve retrieval ranking over time — no ML, just frequency.

    @Entity
    public class AccessLog {
        @Id long id;
        long fileId;
        long conversationId;
        long accessedAt;
        boolean wasHelpful;
    }

### Entity 8: TaskMemory
What the assistant did and how — retained after task completion.
Embeddings are discarded after task. Only the pattern is kept.
This allows future tasks to reuse known approaches without re-embedding.

    @Entity
    public class TaskMemory {
        @Id long id;
        String taskDescription;   // what was asked
        String approach;          // how it was handled (steps taken)
        String[] fileIdsUsed;     // which SourceFile IDs were referenced
        boolean embeddingsDiscarded; // true once Cactus index entries are cleaned up
        long completedAt;
    }

---

## What is NOT stored in ObjectBox

| Thing | Why not |
|-------|---------|
| Raw file content | Stays in original file. No duplication. |
| Full embeddings | Cactus owns these in its custom binary index |
| Model weights | Cactus graph handles quantized weights |
| Renamed files | JarvisOS never renames without user permission — use userAlias instead |

---

## Embedding Lifecycle

Embeddings are NOT permanent by default.

    File indexed
        → cactus_embed() called
        → cactus_index_add() stores in index.bin/data.bin
        → DocumentChunk.embeddingRetained = true
        → Task completes
        → cactus_index_delete() called to remove from Cactus index
        → DocumentChunk.embeddingRetained = false
        → TaskMemory records what was done and how
        → Summary and tags remain in ObjectBox for future Stage 1 retrieval

Re-embedding happens on demand next time the file is needed.

---

## Trickle Indexing Strategy

On file change detected via FileObserver:
  - Add to IndexQueue via WorkManager
  - Constraint: device charging or idle
  - Process 10 files at a time
  - For each file:
      1. Extract text
      2. Chunk into segments
      3. Generate 2-3 sentence summary per chunk
      4. Call cactus_embed() on each chunk
      5. Call cactus_index_add() to register in Cactus
      6. Update SourceFile.isIndexed = true, store cactusIndexId

---

## Query Flow

    User query
        |
        Stage 1: Metadata search (instant)
          - Search Folder.summary, tags for keywords
          - Search SourceFile.fileName, userAlias, tags
          - Check AccessLog for frequently accessed files
          - Check TaskMemory for similar past tasks
          - Returns: candidate SourceFile IDs
        |
        Stage 2: Semantic search (on demand, if Stage 1 insufficient)
          - Call cactus_embed(query)
          - Call cactus_index_query() on shortlist
          - Re-rank by AccessLog.wasHelpful
        |
        Stage 3: Generation
          - Pass top chunks to cactus_complete() with query
          - Stream response via AIDL to calling app
        |
        Stage 4: Cleanup
          - Task complete → cactus_index_delete() for used chunks
          - Save TaskMemory record
          - DocumentChunk.embeddingRetained = false

---

## Next Steps

- [ ] Implement ObjectBox entities in frameworks/base/services/jarvis/db/
- [ ] Implement FileObserver + IndexQueue
- [ ] Implement WorkManager trickle indexing job
- [ ] Wire Stage 1 metadata search (fileName, userAlias, tags, folder summary)
- [ ] Wire Stage 2 cactus_embed + cactus_index_query
- [ ] Implement embedding lifecycle (retain → discard → TaskMemory)
- [ ] Test end-to-end query flow
