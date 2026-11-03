# Demo media

Two small files for the local stack. `scripts/local-seed-media.sh` uploads them through the real upload API, so each
one passes the normal checks and becomes READY, then attaches them to the course `DEMO-MEDIA`.

| File                     | What it is                                                         | Size   |
| ------------------------ | ------------------------------------------------------------------ | ------ |
| `demo-core-banking.pdf`  | 4 text pages (Vietnamese, DejaVu Sans embedded), made with matplotlib | ~28 KB |
| `demo-core-banking.mp4`  | 30 s, 640x360, 15 fps, H.264 yuv420p + AAC, faststart (test pattern and a 440 Hz tone) | ~1.2 MB |

Both were generated on this machine; no third-party content.

```bash
# PDF: matplotlib PdfPages, one figure per page, fonttype 42 so the text stays selectable.
# Video:
ffmpeg -y -f lavfi -i "testsrc2=size=640x360:rate=15:duration=30" \
  -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=30" \
  -c:v libx264 -preset slow -crf 30 -pix_fmt yuv420p -c:a aac -b:a 48k -shortest -movflags +faststart \
  docs/sql/demo-media/demo-core-banking.mp4
```

`lesson-player-modes-demo.sql` fills the chapters and transcript of the demo video block (block 33) on a local DB that
was seeded before those columns existed. Local only; it writes only when both columns are still NULL.
