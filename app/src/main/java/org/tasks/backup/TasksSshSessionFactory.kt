package org.tasks.backup

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import org.eclipse.jgit.internal.transport.ssh.jsch.CredentialsProviderUserInfo
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig.Host
import org.eclipse.jgit.util.FS
import timber.log.Timber
import java.io.File

/**
 * JGit SSH session factory that uses the user-imported SSH private key.
 * Modeled after M2Git's SGitSessionFactory.
 */
class TasksSshSessionFactory(
    private val sshKeyPath: File,
) : JschConfigSessionFactory() {

    override fun configure(host: Host, session: Session) {
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey")

        val credentialsProvider = CredentialsProvider.getDefault()
        if (credentialsProvider != null) {
            val userInfo: UserInfo = CredentialsProviderUserInfo(session, credentialsProvider)
            session.setUserInfo(userInfo)
        }
    }

    @Throws(JSchException::class)
    override fun createDefaultJSch(fs: FS): JSch {
        val jsch = JSch()
        if (sshKeyPath.exists()) {
            Timber.d("Loading SSH key from: ${sshKeyPath.absolutePath}")
            jsch.addIdentity(sshKeyPath.absolutePath)
        } else {
            Timber.w("SSH key file not found: ${sshKeyPath.absolutePath}")
        }
        return jsch
    }
}
