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

package com.tencent.bk.devops.git.core.service

import com.tencent.bk.devops.git.core.api.MockDevopsApi
import com.tencent.bk.devops.git.core.enums.ScmType
import com.tencent.bk.devops.git.core.pojo.GitSourceSettings
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test

class GitSourceProviderTest {

    private var workspace: File = Files.createTempDirectory("git-checkout").toFile()
    private val settings = GitSourceSettings(
        bkWorkspace = workspace.absolutePath,
        pipelineId = "001",
        pipelineBuildId = "122",
        pipelineTaskId = "1243",
        scmType = ScmType.CODE_GIT,
        repositoryUrl = "https://github.com/ci-plugins/git.git",
        repositoryPath = workspace.absolutePath,
        ref = "master",
        enableGitClean = true,
        fetchDepth = 1,
        lfs = true,
        preMerge = false,
        submodules = true,
        persistCredentials = true
    )
    private val devopsApi = MockDevopsApi()

    @Test
    fun getSource() {
        GitSourceProvider(settings = settings, devopsApi = devopsApi).getSource()
    }

    @After
    fun after() {
        GitSourceProvider(settings = settings, devopsApi = devopsApi).cleanUp()
        if (workspace.exists()) {
            workspace.deleteRecursively()
        }
    }
}
