package org.tasks.backup

import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import java.io.File

/**
 * Transport callback that sets up SSH authentication for JGit operations.
 * Modeled after M2Git's SgitTransportCallback.
 */
class GitTransportCallback(
    sshKeyPath: File,
) : TransportConfigCallback {

    private val sshSessionFactory = TasksSshSessionFactory(sshKeyPath)

    override fun configure(transport: Transport) {
        if (transport is SshTransport) {
            transport.sshSessionFactory = sshSessionFactory
        }
    }
}
