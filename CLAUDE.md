# Nabd HMS: project instructions

Always follow the project skill before any design, code, review or documentation work:

@.claude/skills/nabd-dev/SKILL.md

Specialist agents are in `.claude/agents/` (17 specialists + 3 sprint agents). Use them as routed in section 5 of the skill. Do not commit, push or deploy unless asked (a sprint run started with `/sprint` counts as asking for the push in its step 9).

Skills index (project skills, role skills and which to use when): @docs/SKILLS.md.

Scrum-master sprint workflow (start a sprint with `/sprint`): @.claude/SCRUM.md
Live project state: read `../kanban/CONTEXT.md` at the start of every sprint session.

<!-- nabd-sprint:begin -->
## Sprint workflow (team pack)
- **One sprint per chat.** Sprint state lives in `docs/sprints/S<n>.md`: each story with its agent, branch, status and next step. Update it after every story. A new chat resumes by reading that file, never by continuing a long chat. When the context guard fires, update the file and start a new chat.
- **Code map first.** Every agent reads `docs/CODEMAP.md` before anything else. If it is missing, build it once before the sprint: one `nabd-engineer` agent writes at most 200 lines covering modules and packages, where controllers, services, repositories, DTOs and UI screens live, how to build and run tests, and common pitfalls. Refresh it after a big refactor.
- **One fresh agent per story.** Use `nabd-engineer` (Sonnet) to build and `nabd-qa` (Sonnet) to verify. Never send a second story to an agent that already finished one. Run at most 3 at the same time.
- **Opus only in the main chat**, for sprint planning, architecture, or a root cause that Sonnet could not find. Never run engineers or QA on Opus.
- Agents hand back through `docs/sprints/<sprint>/<story>.md` (at most 10 lines). Do not chain SendMessage between agents or poll them. Wait for their completion notice.
<!-- nabd-sprint:end -->
