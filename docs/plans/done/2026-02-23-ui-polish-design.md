# UI Polish Design — DONE

Date: 2026-02-23
Phase: 4 (Polish)
Status: Approved

## Goal

Transform the NVRAM Manager from a functional prototype into a professional, VS Code / JetBrains-style desktop application. Address: flat panel hierarchy, invisible buttons, undiscoverable search, missing hex viewer find, and overall visual roughness.

## Color System & Panel Hierarchy

Three-depth background system replacing the current flat `#2b2b2b` everywhere:

| Surface | Color | Usage |
|---------|-------|-------|
| Deep | `#1e1e1e` | Main content area, table background |
| Panel | `#252526` | Sidebar, detail pane, capacity bar |
| Elevated | `#2d2d2d` | Menu bar, toolbar, table headers, dialogs |
| Border | `#3c3c3c` | Panel separators, input borders |
| Accent | `#3fb950` | Active states, badges, primary buttons |
| Accent subtle | `#1a3a2a` | Selected row background (green tint) |
| Hover | `#2a2d2e` | Hover state on all interactive elements |

### Selection Model
Selected rows use subtle green-tinted background (`#1a3a2a`) with a 2px left accent border instead of solid bright green. Text stays `#e6edf3` (readable) instead of forcing white-on-green.

## Typography Scale

| Role | Size | Weight | Color |
|------|------|--------|-------|
| Section headers | 10px uppercase, 0.5px letter-spacing | bold | `#6e7681` |
| Primary text | 13px | normal | `#e6edf3` |
| Secondary text | 12px | normal | `#8b949e` |
| Muted text | 11px | normal | `#6e7681` |
| Monospace | 12px | normal | `#e6edf3` |

Spacing: 8px base grid. Panel padding: 12px. Row spacing: 4px. Section gaps: 16px.

## Button Tiers

Three distinct button styles, all with `cursor: hand` and visible hover/pressed states:

| Tier | Background | Border | Text | Usage |
|------|-----------|--------|------|-------|
| Primary | `#238636` | none | white | "Add to NVRAM" |
| Secondary | transparent | `#3c3c3c` | `#cccccc` | "View Hex", "Copy", "Go" |
| Danger | `#da3633` | none | white | "Remove from NVRAM" |

Hover: primary brightens to `#2ea043`, secondary fills `#2d2d2d`, danger brightens to `#f85149`.

## Component Changes

### ContentListPane — Collapsible Search
- Replace always-visible TextField with a compact breadcrumb bar
- Search icon button on the right side of the breadcrumb bar
- Clicking icon (or Ctrl+F) expands search field with slide animation
- Escape or X button collapses field and clears filter
- Search field gets focus immediately on expand

### HexViewerDialog — Find Bar
- Add find bar below the existing toolbar (offset + go + copy)
- TextField for search query, toggle between hex (`A0 FF`) and ASCII mode
- Next/Previous buttons to cycle through matches
- Matching rows highlighted with accent background
- Escape dismisses find bar
- Ctrl+F opens find bar

### SidebarPane — Visual Polish
- Subtle right border (`#3c3c3c`) separating sidebar from content
- NVRAM entries: small colored dot indicator (green = saved, orange = modified)
- Section headers: bottom border separator line
- Panel background: `#252526` distinct from content area `#1e1e1e`

### DetailPane — Button Clarity
- "View Hex" button uses secondary style (outlined, clearly a button)
- "Add to NVRAM" stays primary green
- "Remove from NVRAM" stays danger red
- All buttons have visible borders and hover states even when not focused

### CapacityBar — Richer Status
- Subtle top border separating from content
- Show file count alongside KB usage: "12 files | 192 KB / 512 KB (37%)"
- Panel background matches sidebar (`#252526`)

## CSS Overhaul Scope

Full rewrite of `dark-theme.css` covering:
- Root variables for the 3-depth system
- Table: row striping with `#1e1e1e` / `#222222`, selection with accent-subtle
- List cells: same selection model as table
- Scrollbars: thin, rounded thumb, hidden track
- Menu bar and context menus: elevated background
- Text fields: proper border, focus ring with accent color
- Progress bar: panel background track, accent fill
- Dialogs: elevated background, proper button styling
- All interactive elements: hover and focus states

## Files Modified

| File | Changes |
|------|---------|
| `dark-theme.css` | Full rewrite |
| `ContentListPane.java` | Collapsible search bar with icon toggle |
| `HexViewerDialog.java` | Find bar (hex/ASCII search, highlight, nav) |
| `DetailPane.java` | Button style class updates |
| `SidebarPane.java` | Modified dot, section borders, panel background |
| `CapacityBar.java` | File count, top border |

No new files. No test changes needed (all visual/CSS).
