# Demo video

The demo video is not kept in the repository. A silent, roughly 10 MB webm would stay in the git history for good, and every re-recording would add another copy. Generate it locally instead:

```sh
sh scripts/demo/up.sh              # the local demo stack on 127.0.0.1:28080
cd frontend && npm run video:demo  # writes docs/media/break-it-demo.webm
```

The script (`frontend/e2e/demo-video.spec.ts`) opens the demo page, clicks the eight actions one after another with a two-second pause each, and stops on the invariants panel and the counters. Generated videos here are ignored by git.
