// Terraform registry plugin for Artifactory PRO

import org.artifactory.repo.RepoPath
import org.artifactory.repo.RepoPathFactory
import org.artifactory.repo.service.InternalRepositoryService

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import java.util.regex.Matcher


baseUrl = "https://artifactory.example.com" // Change to your Artifactory instance base URL

virtualRepoKey = "terraform-registry"
localRepoKey = "${virtualRepoKey}-local"
remoteRepoKey = "${virtualRepoKey}-proxy"
remoteCacheRepoKey = "${remoteRepoKey}-cache" // created by Artifactory

download {
    // For remote uncached resources, provide an alternative download content from the source
    // in X-Terraform-Get header fetched from the same path in registry.terraform.io
    altRemoteContent { responseRepoPath ->
        if (responseRepoPath.repoKey != remoteRepoKey) return

        switch(responseRepoPath.path) {
            case ~/^v1\/modules\/\S+\/download$/:
                // Read module source URL from https://registry.terraform.io X-Terraform-Get header
                // Process header value:
                //   * git::https to plain https URL
                def upstreamURL = 'https://registry.terraform.io/' + responseRepoPath.path
                conn = new URL(upstreamURL).openConnection()
                origSource = conn.getHeaderField("X-Terraform-Get")
                conn.inputStream.close()
                switch(origSource) {
                    case ~/^git::https:\/\/github\.com\/([\w\-]+)\/([\w\-]+)\?ref=([\w\.\-]+)$/:
                        m = Matcher.lastMatcher
                        processedSource = "https://github.com/${m[0][1]}/${m[0][2]}/archive/${m[0][3]}.tar.gz"
                        break
                    default:
                        processedSource = origSource
                        break
                }
                conn = new URL(processedSource).openConnection()
                inputStream = conn.inputStream
                break
            case ~/^v1\/providers\/hashicorp\/\S+\/download\/\w+\/\w+$/:
                def repoService = ctx.beanForType(InternalRepositoryService)
                def repo = repoService.remoteRepositoryByKey(responseRepoPath.repoKey)
                def hashicorpProxyUrl = "https://${baseUrl}/artifactory/vendors-hashicorp"
                def streamhandle = repo.downloadResource(responseRepoPath.path)
                def json = new JsonSlurper().parse(streamhandle.inputStream)
                streamhandle?.close()
                json.download_url = json.download_url.replace('https://releases.hashicorp.com', hashicorpProxyUrl)
                json.shasums_url = json.shasums_url.replace('https://releases.hashicorp.com', hashicorpProxyUrl)
                json.shasums_signature_url = json.shasums_signature_url.replace('https://releases.hashicorp.com', hashicorpProxyUrl)
                String output = new JsonBuilder(json).toPrettyString()
                def bytes = output.bytes
                inputStream = new ByteArrayInputStream(bytes)
                size = bytes.length
                break
            default:
                break
        }
    }

    // When retrieving a cached or remote artefact, return HTTP 204 and set X-Terraform-Get for Terraform
    // to retrieve module source from Artifactory
    altResponse { request, responseRepoPath ->
        if (!isModuleDownload(responseRepoPath)) return

        status = 204
        headers["X-Terraform-Get"] = './archive.tar.gz'
    }

    // Artifactory cache paths are the same as remote paths, and there seems to be no way to alter cache path,
    // therefore the cache entry is accessible by /download endpoint. But the /download endpoint is configured
    // to return no content by altResponse.
    // When downloading a cached or local artefact, alter HTTP 404 download error to return content from /download.
    afterDownloadError { request ->
        def reqRepoPath = request.getRepoPath()
        if (!isModuleVirtualDownload(reqRepoPath)) return

        def downloadPath = reqRepoPath.path - '/archive.tar.gz' + '/download'

        def getContentInputStream = { String repoKey, String path ->
            def repoPath = RepoPathFactory.create(repoKey, path)
            if (repositories.exists(repoPath)) {
                repositories.getContent(repoPath).inputStream
            }
        }

        inputStream = getContentInputStream(remoteCacheRepoKey, downloadPath)
        if (!inputStream) inputStream = getContentInputStream(localRepoKey, downloadPath)
        if (inputStream) status = 200
    }
}


def isModuleDownload(RepoPath repoPath) {
    return repoPath.path.startsWith('v1/modules/') &&
           repoPath.path.endsWith('/download') &&
           (repoPath.repoKey == remoteRepoKey || repoPath.repoKey == remoteCacheRepoKey || repoPath.repoKey == localRepoKey)
}

def isModuleVirtualDownload(RepoPath repoPath) {
    return repoPath.path.endsWith('/archive.tar.gz') &&
           repoPath.repoKey == virtualRepoKey
}
