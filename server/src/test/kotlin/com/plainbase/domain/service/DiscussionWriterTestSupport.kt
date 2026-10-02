package com.plainbase.domain.service

import com.plainbase.domain.principal.discussionGrantForTests

fun DiscussionWriter.write(command: DiscussionCommand, reliedOn: ReliedOn = ReliedOn()): DiscussionWriteOutcome =
    write(discussionGrantForTests(command.root, command.action(), reliedOn), command)
