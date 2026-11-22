<#-- Sign-up step 3 (IAM-08), after a verified code, when an account already uses the identifier: shown only to its owner. -->
<#import "template.ftl" as layout>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "header">
        ${msg("shpExistsTitle")}
    <#elseif section = "form">
        <p id="shp-exists-text">${msg("shpExistsText")}</p>
        <@buttons.actionGroup>
            <@buttons.buttonLink id="shp-exists-login" href=url.loginUrl label="doLogIn" class=["kcButtonPrimaryClass", "kcButtonBlockClass"]/>
            <@buttons.buttonLink id="shp-exists-reset" href=url.loginResetCredentialsUrl label="doForgotPassword"/>
        </@buttons.actionGroup>
    </#if>
</@layout.registrationLayout>
