# Plainbase

Filesystem-native internal docs: ordinary Markdown files in configured roots are the authoritative
content, and people and agents read, discuss and change them through Plainbase.

## Language

### Discussions

**Discussion**:
A continuing exchange about a page or a passage within it, made of Comments, which may stay unresolved.
It is durable user content, not derived state.
_Avoid_: Thread, comment thread, annotation (as a separate concept)

**Comment**:
One entry in a Discussion, with its author and time. The first Comment opens the Discussion.
_Avoid_: Reply, note, message

**Anchor**:
The passage a Discussion was started on, recorded as it was at that moment. It never changes; where the
passage sits in today's page is worked out afresh and is only ever a current match.
_Avoid_: Marker, pin, block id

**Reattach**:
A person deliberately pointing a Discussion at the passage's current text when it no longer matches.
The original Anchor is kept alongside.
_Avoid_: Re-anchor (that is the automatic matching), move

**Orphaned discussion**:
A Discussion whose page can no longer be found, neither by its id nor by the path it was started on.
It keeps showing its original target rather than disappearing.
_Avoid_: Detached (already means a row whose root is no longer configured), lost, broken
