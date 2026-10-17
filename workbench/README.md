# Nabd Engineering Workbench

The shared task and subtask tracker the engineering team works from. Every **story** is a requirement for a **persona**, owned by one **service/module**, with its **API contract**, **database changes**, **events**, **UI screen** (linked to the prototype), **acceptance criteria**, **tests** and **subtasks**. Status, assignee, sprint, subtask ticks and comments are shared live.

## Where it runs

- **Shared (recommended):** the published claude.ai page. The owner shares it with the team at **Contributor** level so engineers can update status, assign themselves, tick subtasks and comment; Viewers see the plan read-only. Changes appear for everyone instantly.
- **Locally:** `cd workbench && python3 -m http.server 8090`, then open http://localhost:8090. Local mode saves team state in that browser only (the rail says so); use it to read the plan or try things.

## Views

| View | Use it to |
|---|---|
| Overview | See progress by domain, phase, persona and service; blocked work; open P0s |
| Board | Move stories between Backlog → Ready → In progress → In review → Blocked → Done (drag or use the story's status menu) |
| Backlog | Read everything grouped by domain and epic; export the filtered list as CSV |
| My work | Your assigned stories |
| Sprints | Plan by two-week sprint (S0 starts Mon 2 Nov 2026); drag stories between sprints |
| Personas | Read the requirements as each persona experiences them |
| Services & contracts | The microservice map: per-service modules, the API catalogue, data changes and the event catalogue |
| Tracker map | How each task in `docs/Nabd-Development-Tracker.xlsx` maps to stories |
| Open questions | Questions the principal engineers raised; resolve before the affected stories start |

Deep links: `#board`, `#services`, or a story id such as `#ACC-001`.

## Working agreement

1. Pick a story in **Ready**, **Assign to me**, set the sprint, move it to **In progress**.
2. Work the subtasks top to bottom; tick each when merged.
3. Name branches and PRs with the story id (`ACC-001-accept-booking`); post the PR link as a comment.
4. Move to **In review** when all subtasks are ticked and the acceptance criteria pass in a preview environment; **Done** after merge and QA sign-off.
5. Stuck? Move to **Blocked** and say why in a comment.

## Updating the plan

Specs live per domain (`PLT`, `ACC`, `COM`, `CLN`, `PHL`, `FIN`, `IDC`, `HOS`, `ADM`, `FEF`, `OPS`). To change them, edit the domain JSON in `workbench/specs/` and regenerate the bundle:

```bash
python3 workbench/tools/merge_specs.py workbench/specs workbench/specs/tracker_tasks.json
```

The script validates personas, services, duplicate ids and dependencies, and reports tracker tasks no story covers. Team state (status, comments) is stored separately and survives regeneration as long as story ids don't change; never renumber a story that has started.
