<#-- Sign-up step 3 (IAM-08), after a verified code and only when no account uses the identifier: choose a password. -->
<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('password','password-confirm'); section>
    <#if section = "header">
        ${msg("shpPasswordTitle")}
    <#elseif section = "form">
        <p id="shp-account-for">${msg("shpAccountFor", shpDestination)}</p>
        <form id="shp-register-password-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post" novalidate="novalidate">
            <@field.password name="password" label=msg("password") autocomplete="new-password" autofocus=true />
            <p id="shp-password-help" class="${properties.kcLoginMainFooterHelperText!}">${msg("shpPasswordHelp")}</p>
            <@field.password name="password-confirm" label=msg("passwordConfirm") autocomplete="new-password" />
            <@buttons.actionGroup>
                <@buttons.button id="kc-create-account" label="shpCreateAccount"/>
            </@buttons.actionGroup>
        </form>
    </#if>
</@layout.registrationLayout>
