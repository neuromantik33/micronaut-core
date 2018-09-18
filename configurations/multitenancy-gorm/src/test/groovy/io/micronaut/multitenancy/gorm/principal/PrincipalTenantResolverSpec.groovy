package io.micronaut.multitenancy.gorm.principal

import io.micronaut.context.ApplicationContext
import io.micronaut.context.env.Environment
import io.micronaut.core.io.socket.SocketUtils
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.RxHttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.security.authentication.UsernamePasswordCredentials
import io.micronaut.security.endpoints.LoginController
import io.micronaut.security.token.jwt.render.BearerAccessRefreshToken
import io.micronaut.security.token.writer.TokenWriter
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise

@Stepwise
class PrincipalTenantResolverSpec extends Specification {

    static final SPEC_NAME_PROPERTY = 'spec.name'

    @Shared
    int gormPort

    @Shared
    String sherlockAccessToken

    @Shared
    String watsonAccessToken

    @AutoCleanup
    @Shared
    EmbeddedServer gormEmbeddedServer

    @AutoCleanup
    @Shared
    RxHttpClient gormClient

    @AutoCleanup
    @Shared
    EmbeddedServer gatewayEmbeddedServer

    @AutoCleanup
    @Shared
    RxHttpClient gatewayClient

    def setupSpec() {
        gormPort = SocketUtils.findAvailableTcpPort()
    }

    def "setup gorm server"() {
        given:
        Map gormConfig = [
                'micronaut.server.port'                       : gormPort,
                (SPEC_NAME_PROPERTY)                          : 'multitenancy.principal.gorm',
                'grails.gorm.multiTenancy.mode'               : 'DISCRIMINATOR',
                'grails.gorm.multiTenancy.tenantResolverClass': 'io.micronaut.multitenancy.gorm.PrincipalTenantResolver',
                'micronaut.security.enabled': true,
                'micronaut.security.token.jwt.enabled': true,
                'micronaut.security.token.jwt.signatures.secret.generator.secret': 'pleaseChangeThisSecretForANewOne',
                'hibernate.hbm2ddl.auto'                      : 'create-drop',
                'datasources.default.url'                     :'jdbc:h2:mem:principalGormDb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE',
                'datasources.default.username'                :'sa',
                'datasources.default.password'                :'',
                'datasources.default.driverClassName'         :'org.h2.Driver',
        ]

        gormEmbeddedServer = ApplicationContext.run(EmbeddedServer, gormConfig, Environment.TEST)

        gormClient = gormEmbeddedServer.applicationContext.createBean(RxHttpClient, gormEmbeddedServer.getURL())

        when:
        for (Class beanClazz : [BookService, BooksController, Bootstrap]) {
            gormEmbeddedServer.applicationContext.getBean(beanClazz)
        }

        then:
        noExceptionThrown()
    }

    def "books endpoints are secured"() {
        when:
        gormClient.toBlocking().exchange(HttpRequest.GET('/api/books'))

        then:
        def e = thrown(HttpClientResponseException)
        e.response.status() == HttpStatus.UNAUTHORIZED
    }

    def "setup gateway server"() {
        given:
        Map gatewayConfig = [
                (SPEC_NAME_PROPERTY): 'multitenancy.principal.gateway',
                'micronaut.security.enabled': true,
                'micronaut.security.endpoints.login.enabled': true,
                'micronaut.security.token.jwt.enabled': true,
                'micronaut.security.token.jwt.signatures.secret.generator.secret': 'pleaseChangeThisSecretForANewOne',
                'micronaut.http.services.books.url': "http://localhost:${gormPort}",
                'micronaut.security.token.writer.header.enabled': true,
                'micronaut.security.token.propagation.enabled': true,
                'micronaut.security.token.propagation.service-id-regex': 'books',
        ]

        gatewayEmbeddedServer = ApplicationContext.run(EmbeddedServer, gatewayConfig, Environment.TEST)

        when:
        for (Class beanClazz : [AuthenticationProviderUserPassword, GatewayController, BooksClient, LoginController, TokenWriter]) {
            gatewayEmbeddedServer.applicationContext.getBean(beanClazz)
        }

        then:
        noExceptionThrown()

        when:
        gatewayClient = gatewayEmbeddedServer.applicationContext.createBean(RxHttpClient, gatewayEmbeddedServer.getURL())

        then:
        noExceptionThrown()
    }

    def "gateway endpoints are secured"() {
        when:
        gatewayClient.toBlocking().exchange(HttpRequest.GET('/'))

        then:
        def e = thrown(HttpClientResponseException)
        e.response.status() == HttpStatus.UNAUTHORIZED
    }

    def "login to gateway to get JWT for sherlock and watson"() {
        when:
        UsernamePasswordCredentials creds = new UsernamePasswordCredentials('sherlock', 'elementary')
        HttpResponse rsp = gatewayClient.toBlocking().exchange(HttpRequest.POST('/login', creds), BearerAccessRefreshToken)

        then:
        rsp.status() == HttpStatus.OK
        rsp.body().accessToken

        when:
        this.sherlockAccessToken = rsp.body().accessToken

        then:
        sherlockAccessToken

        when:
        creds = new UsernamePasswordCredentials('watson', 'elementary')
        rsp = gatewayClient.toBlocking().exchange(HttpRequest.POST('/login', creds), BearerAccessRefreshToken)

        then:
        rsp.status() == HttpStatus.OK
        rsp.body().accessToken

        when:
        this.watsonAccessToken = rsp.body().accessToken

        then:
        watsonAccessToken
    }

    def "fetch books for watson and sherlock directly from the books microservice, the principal included in the JWT is tenant ID. They get only their books"() {
        when:
        HttpResponse rsp = gormClient.toBlocking().exchange(HttpRequest.GET('/api/books').bearerAuth(sherlockAccessToken), Argument.of(List, String))

        then:
        rsp.status() == HttpStatus.OK
        rsp.body().size() == 1
        ['Sherlock diary'] == rsp.body()

        when:
        rsp = gormClient.toBlocking().exchange(HttpRequest.GET('/api/books').bearerAuth(watsonAccessToken), Argument.of(List, String))

        then:
        rsp.status() == HttpStatus.OK
        rsp.body().size() == 1
        ['Watson diary'] == rsp.body()
    }

    def "fetch books for watson and sherlock, since the principal included in the JWT is tenant ID. They get only their books"() {
        when:
        HttpResponse rsp = gatewayClient.toBlocking().exchange(HttpRequest.GET('/').bearerAuth(sherlockAccessToken), Argument.of(List, String))

        then:
        rsp.status() == HttpStatus.OK
        rsp.body().size() == 1
        ['Sherlock diary'] == rsp.body()

        when:
        rsp = gatewayClient.toBlocking().exchange(HttpRequest.GET('/').bearerAuth(watsonAccessToken), Argument.of(List, String))

        then:
        rsp.status() == HttpStatus.OK
        rsp.body().size() == 1
        ['Watson diary'] == rsp.body()
    }
}