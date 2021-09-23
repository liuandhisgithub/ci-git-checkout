/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bk.devops.git.core.service.helper

import com.tencent.bk.devops.git.core.constant.GitConstants.BK_CI_BUILD_JOB_ID
import com.tencent.bk.devops.git.core.constant.GitConstants.CREDENTIAL_JAVA_PATH
import com.tencent.bk.devops.git.core.constant.GitConstants.GIT_CREDENTIAL_COMPATIBLEHOST
import com.tencent.bk.devops.git.core.constant.GitConstants.GIT_CREDENTIAL_HELPER
import com.tencent.bk.devops.git.core.constant.GitConstants.GIT_REPO_PATH
import com.tencent.bk.devops.git.core.enums.GitConfigScope
import com.tencent.bk.devops.git.core.enums.OSType
import com.tencent.bk.devops.git.core.exception.ParamInvalidException
import com.tencent.bk.devops.git.core.pojo.CredentialArguments
import com.tencent.bk.devops.git.core.pojo.GitSourceSettings
import com.tencent.bk.devops.git.core.service.GitCommandManager
import com.tencent.bk.devops.git.core.util.AgentEnv
import com.tencent.bk.devops.git.core.util.CommandUtil
import com.tencent.bk.devops.git.core.util.EnvHelper
import com.tencent.bk.devops.git.core.util.GitUtil
import com.tencent.bk.devops.git.core.util.SSHAgentUtils
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.net.URL

@Suppress("ALL")
class GitAuthHelper(
    private val git: GitCommandManager,
    private val settings: GitSourceSettings
) : IGitAuthHelper {

    companion object {
        private val logger = LoggerFactory.getLogger(GitAuthHelper::class.java)
    }

    private val serverInfo = GitUtil.getServerInfo(settings.repositoryUrl)

    private val credentialHome = File(System.getProperty("user.home"), ".checkout").absolutePath
    private val credentialJarPath = File(credentialHome, "git-checkout-credential.jar").absolutePath
    private val credentialShellPath = File(credentialHome, "git-checkout-credential.sh").absolutePath
    private val credentialBatPath = File(credentialHome, "git-checkout-credential.bat").absolutePath

    private fun configureHttp() {
        if (!serverInfo.httpProtocol ||
            settings.username.isNullOrBlank() ||
            settings.password.isNullOrBlank()
        ) {
            return
        }

        val compatibleHostList = settings.compatibleHostList
        if (!compatibleHostList.isNullOrEmpty() && compatibleHostList.contains(serverInfo.hostName)) {
            git.config(
                configKey = GIT_CREDENTIAL_COMPATIBLEHOST,
                configValue = compatibleHostList.joinToString(","),
                configScope = GitConfigScope.GLOBAL
            )
        }
        EnvHelper.addEnvVariable("${CREDENTIAL_JAVA_PATH}_$BK_CI_BUILD_JOB_ID", getJavaFilePath())
        git.setEnvironmentVariable("${CREDENTIAL_JAVA_PATH}_$BK_CI_BUILD_JOB_ID", getJavaFilePath())
        install()
        store()
        insteadOf()
    }

    private fun install() {
        val credentialJarParentFile = File(credentialHome)
        if (!credentialJarParentFile.exists()) {
            credentialJarParentFile.mkdirs()
        }
        javaClass.classLoader.getResourceAsStream("script/git-checkout-credential.jar")?.use { inputStream ->
            copyCredentialFile(
                sourceInputStream = inputStream,
                targetFile = File(credentialJarPath)
            )
        }

        // 先卸载本地的git凭证,为了兼容历史配置
        git.tryConfigUnset(
            configKey = GIT_CREDENTIAL_HELPER
        )

        if (AgentEnv.getOS() != OSType.WINDOWS) {
            javaClass.classLoader.getResourceAsStream("script/git-checkout-credential.sh")?.use { inputStream ->
                copyCredentialFile(
                    sourceInputStream = inputStream,
                    targetFile = File(credentialShellPath)
                )
            }
            // 安装
            git.config(
                configKey = GIT_CREDENTIAL_HELPER,
                configValue = "!sh '$credentialShellPath'",
                configScope = GitConfigScope.GLOBAL
            )
        } else {
            javaClass.classLoader.getResourceAsStream("script/git-checkout-credential.bat")?.use { inputStream ->
                copyCredentialFile(
                    sourceInputStream = inputStream,
                    targetFile = File(credentialBatPath)
                )
            }
            // 安装
            git.config(
                configKey = GIT_CREDENTIAL_HELPER,
                configValue = "!bash '$credentialBatPath'",
                configScope = GitConfigScope.GLOBAL
            )
        }
    }

    private fun store() {
        with(URL(settings.repositoryUrl).toURI()) {
            CommandUtil.execute(
                executable = getJavaFilePath(),
                args = listOf(
                    "-Dfile.encoding=utf-8",
                    "-Ddebug=${settings.enableTrace}",
                    "-jar",
                    credentialJarPath,
                    "devopsStore"
                ),
                runtimeEnv = mapOf(
                    GIT_REPO_PATH to settings.repositoryPath
                ),
                inputStream = CredentialArguments(
                    protocol = scheme,
                    host = host,
                    path = path.removePrefix("/"),
                    username = settings.username,
                    password = settings.password
                ).convertInputStream()
            )
        }
    }

    private fun copyCredentialFile(sourceInputStream: InputStream, targetFile: File) {
        if (!targetFile.exists()) {
            FileUtils.copyToFile(sourceInputStream, targetFile)
        } else {
            val newFileMd5 = DigestUtils.md5Hex(sourceInputStream)
            val oldFileMd5 = targetFile.inputStream().use { DigestUtils.md5Hex(it) }
            if (newFileMd5 != oldFileMd5) {
                targetFile.delete()
                FileUtils.copyToFile(sourceInputStream, targetFile)
            }
        }
    }

    private fun insteadOf() {
        httpInsteadOfGit(host = serverInfo.hostName)

        // 配置其他域名权限
        val compatibleHostList = settings.compatibleHostList
        if (!compatibleHostList.isNullOrEmpty() && compatibleHostList.contains(serverInfo.hostName)) {
            compatibleHostList.filter { it != serverInfo.hostName }.forEach { otherHostName ->
                httpInsteadOfGit(host = otherHostName)
            }
        }
    }

    private fun httpInsteadOfGit(host: String) {
        val insteadOfKey = "url.${serverInfo.origin}/.insteadOf"
        // 把全局的insteadOf先去掉
        if (git.configExists(
                configKey = "url.git@$host:.insteadof",
                configScope = GitConfigScope.GLOBAL
            )
        ) {
            git.tryConfigUnset(
                configKey = "url.git@$host:.insteadof",
                configScope = GitConfigScope.GLOBAL
            )
        }
        // 如果没有配置使用http替换ssh配置
        if (!git.configExists(
                configKey = insteadOfKey,
                configValueRegex = "git@$host:"
            )
        ) {
            git.configAdd(
                configKey = insteadOfKey,
                configValue = "git@$host:"
            )
        }
    }

    private fun configureSsh() {
        if (serverInfo.httpProtocol) {
            return
        }
        if (settings.privateKey.isNullOrBlank()) {
            throw ParamInvalidException(errorMsg = "private key must not be empty")
        }
        SSHAgentUtils().addIdentity(privateKey = settings.privateKey, passPhrase = settings.passPhrase)
        val insteadOfKey = "url.${serverInfo.origin}:.insteadOf"
        git.tryConfigUnset(
            configKey = insteadOfKey
        )
        listOf(
            "http://${serverInfo.hostName}/",
            "https://${serverInfo.hostName}/"
        ).forEach {
            git.configAdd(
                configKey = insteadOfKey,
                configValue = it
            )
        }

        settings.compatibleHostList?.filter { it != serverInfo.hostName }?.forEach { otherHostName ->
            listOf("http", "https").forEach { protocol ->
                git.configAdd(
                    configKey = insteadOfKey,
                    configValue = "$protocol://$otherHostName/"
                )
            }
        }
    }

    private fun getJavaFilePath() = File(System.getProperty("java.home"), "/bin/java").absolutePath

    override fun configureAuth() {
        configureHttp()
        configureSsh()
    }

    override fun removeAuth() {
        if (!serverInfo.httpProtocol ||
            settings.username.isNullOrBlank() ||
            settings.password.isNullOrBlank() ||
            !File(credentialJarPath).exists()
        ) {
            return
        }
        // 删除凭证
        with(URL(settings.repositoryUrl).toURI()) {
            CommandUtil.execute(
                executable = getJavaFilePath(),
                args = listOf(
                    "-Dfile.encoding=utf-8",
                    "-Ddebug=${settings.enableTrace}",
                    "-jar",
                    credentialJarPath,
                    "devopsErase"
                ),
                runtimeEnv = mapOf(
                    GIT_REPO_PATH to settings.repositoryPath
                ),
                inputStream = CredentialArguments(
                    protocol = scheme,
                    host = host,
                    path = path.removePrefix("/")
                ).convertInputStream()
            )
        }
    }
}
