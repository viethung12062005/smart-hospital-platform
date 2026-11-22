<#-- Sign-up step 1 (IAM-08): phone number or email, and the terms of use. Built from keycloak.v2's macros. -->
<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('identifier','termsAccepted'); section>
    <#if section = "header">
        ${msg("registerTitle")}
    <#elseif section = "form">
        <form id="shp-register-identifier-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post" novalidate="novalidate">
            <@field.input name="identifier" label=msg("shpIdentifier") value=(identifier!'') autocomplete="username" autofocus=true />
            <p id="shp-identifier-help" class="${properties.kcLoginMainFooterHelperText!}">${msg("shpIdentifierHelp")}</p>

            <div class="${properties.kcFormGroupClass}">
                <div id="kc-registration-terms-text">
                    <strong>${msg("termsTitle")}</strong>
                    ${kcSanitize(msg("termsText"))?no_esc}
                </div>
                <div class="${properties.kcCheckboxClass!}">
                    <input type="checkbox" id="termsAccepted" name="termsAccepted" class="${properties.kcCheckboxInputClass!}"
                           aria-invalid="<#if messagesPerField.existsError('termsAccepted')>true</#if>"
                           <#if messagesPerField.existsError('termsAccepted')>aria-describedby="input-error-terms-accepted"</#if>/>
                    <label for="termsAccepted" class="${properties.kcCheckboxLabelClass!}">${msg("acceptTerms")}</label>
                </div>
                <#if messagesPerField.existsError('termsAccepted')>
                    <span id="input-error-terms-accepted" class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
                        ${kcSanitize(messagesPerField.get('termsAccepted'))?no_esc}
                    </span>
                </#if>
            </div>

            <@buttons.actionGroup>
                <@buttons.button id="kc-register" label="shpSendCode"/>
                <@buttons.buttonLink href=url.loginUrl label="backToLogin"/>
            </@buttons.actionGroup>
        </form>
    </#if>
</@layout.registrationLayout>
