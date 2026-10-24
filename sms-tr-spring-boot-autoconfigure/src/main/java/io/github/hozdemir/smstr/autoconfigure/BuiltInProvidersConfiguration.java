package io.github.hozdemir.smstr.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import io.github.hozdemir.smstr.http.ProviderHttp;
import io.github.hozdemir.smstr.provider.iletimerkezi.IletiMerkeziSmsProvider;
import io.github.hozdemir.smstr.provider.mutlucell.MutlucellSmsProvider;
import io.github.hozdemir.smstr.provider.netgsm.NetgsmSmsProvider;
import io.github.hozdemir.smstr.provider.vatansms.VatanSmsProvider;
import io.github.hozdemir.smstr.provider.verimor.VerimorSmsProvider;

/** A built-in provider is registered once its credentials are configured. */
@Configuration(proxyBeanMethods = false)
class BuiltInProvidersConfiguration {

    @Bean
    @ConditionalOnProperty("sms.netgsm.username")
    @ConditionalOnMissingBean(NetgsmSmsProvider.class)
    NetgsmSmsProvider netgsmSmsProvider(SmsProperties properties, ObjectProvider<RestClient.Builder> builder) {
        SmsProperties.Netgsm netgsm = properties.netgsm();
        return new NetgsmSmsProvider(new NetgsmSmsProvider.Settings(
                required(netgsm.username(), "sms.netgsm.username"),
                required(netgsm.password(), "sms.netgsm.password"),
                required(netgsm.sender(), "sms.netgsm.sender"),
                netgsm.appName(), netgsm.otpEndpoint()),
                restClient(builder, netgsm.baseUrl(), properties));
    }

    @Bean
    @ConditionalOnProperty("sms.iletimerkezi.api-key")
    @ConditionalOnMissingBean(IletiMerkeziSmsProvider.class)
    IletiMerkeziSmsProvider iletiMerkeziSmsProvider(SmsProperties properties,
            ObjectProvider<RestClient.Builder> builder) {
        SmsProperties.IletiMerkezi iletiMerkezi = properties.iletimerkezi();
        return new IletiMerkeziSmsProvider(new IletiMerkeziSmsProvider.Settings(
                required(iletiMerkezi.apiKey(), "sms.iletimerkezi.api-key"),
                required(iletiMerkezi.apiHash(), "sms.iletimerkezi.api-hash"),
                required(iletiMerkezi.sender(), "sms.iletimerkezi.sender")),
                restClient(builder, iletiMerkezi.baseUrl(), properties));
    }

    @Bean
    @ConditionalOnProperty("sms.verimor.username")
    @ConditionalOnMissingBean(VerimorSmsProvider.class)
    VerimorSmsProvider verimorSmsProvider(SmsProperties properties, ObjectProvider<RestClient.Builder> builder) {
        SmsProperties.Verimor verimor = properties.verimor();
        return new VerimorSmsProvider(new VerimorSmsProvider.Settings(
                required(verimor.username(), "sms.verimor.username"),
                required(verimor.password(), "sms.verimor.password"),
                verimor.sender()),
                restClient(builder, verimor.baseUrl(), properties));
    }

    @Bean
    @ConditionalOnProperty("sms.mutlucell.username")
    @ConditionalOnMissingBean(MutlucellSmsProvider.class)
    MutlucellSmsProvider mutlucellSmsProvider(SmsProperties properties, ObjectProvider<RestClient.Builder> builder) {
        SmsProperties.Mutlucell mutlucell = properties.mutlucell();
        return new MutlucellSmsProvider(new MutlucellSmsProvider.Settings(
                required(mutlucell.username(), "sms.mutlucell.username"),
                required(mutlucell.password(), "sms.mutlucell.password"),
                required(mutlucell.sender(), "sms.mutlucell.sender"),
                mutlucell.brandCode()),
                restClient(builder, mutlucell.baseUrl(), properties));
    }

    @Bean
    @ConditionalOnProperty("sms.vatansms.api-id")
    @ConditionalOnMissingBean(VatanSmsProvider.class)
    VatanSmsProvider vatanSmsProvider(SmsProperties properties, ObjectProvider<RestClient.Builder> builder) {
        SmsProperties.VatanSms vatanSms = properties.vatansms();
        return new VatanSmsProvider(new VatanSmsProvider.Settings(
                required(vatanSms.apiId(), "sms.vatansms.api-id"),
                required(vatanSms.apiKey(), "sms.vatansms.api-key"),
                required(vatanSms.sender(), "sms.vatansms.sender")),
                restClient(builder, vatanSms.baseUrl(), properties));
    }

    private static RestClient restClient(ObjectProvider<RestClient.Builder> builder, String baseUrl,
            SmsProperties properties) {
        return ProviderHttp.restClient(builder.getIfAvailable(RestClient::builder), baseUrl,
                properties.timeout().connect(), properties.timeout().read());
    }

    private static String required(@Nullable String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set");
        }
        return value;
    }
}
