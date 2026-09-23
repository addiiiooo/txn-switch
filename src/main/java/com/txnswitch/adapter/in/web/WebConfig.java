/* SPDX-License-Identifier: MIT */
package com.txnswitch.adapter.in.web;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets a controller declare {@link MerchantPrincipal} as a parameter.
 *
 * <p>The point is that there is no other way to obtain a merchant id in a handler: it arrives from
 * the authentication filter or not at all.
 */
@Configuration
class WebConfig implements WebMvcConfigurer {

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new MerchantPrincipalArgumentResolver());
  }

  private static final class MerchantPrincipalArgumentResolver
      implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
      return MerchantPrincipal.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer mavContainer,
        NativeWebRequest request,
        WebDataBinderFactory binderFactory) {
      Object principal =
          request.getAttribute(
              ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
      if (principal == null) {
        // Unreachable through the filter chain; a programming error if it ever happens.
        throw new IllegalStateException("no authenticated merchant on this request");
      }
      return principal;
    }
  }
}
