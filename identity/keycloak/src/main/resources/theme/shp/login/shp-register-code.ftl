<#-- Sign-up step 2 (IAM-08): the one-time code. The same page whether or not an account uses the identifier. -->
<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${msg("shpCodeTitle")}
    <#elseif section = "form">
        <p id="shp-code-sent">
            <#if shpChannel == "phone">${msg("shpCodeSentPhone", shpDestination)}<#else>${msg("shpCodeSentEmail", shpDestination)}</#if>
        </p>
        <form id="shp-register-code-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post" novalidate="novalidate">
            <@field.input name="code" label=msg("shpCode") autocomplete="one-time-code" autofocus=true />
            <@buttons.actionGroup>
                <@buttons.button id="kc-verify" label="shpVerify"/>
            </@buttons.actionGroup>
        </form>
        <form id="shp-register-code-other" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <@buttons.actionGroup>
                <@buttons.button id="kc-resend" name="resend" type="secondary" label="shpResendCode"/>
                <@buttons.button id="kc-change" name="change" type="link" label="shpChangeIdentifier"/>
            </@buttons.actionGroup>
        </form>
    </#if>
</@layout.registrationLayout>
