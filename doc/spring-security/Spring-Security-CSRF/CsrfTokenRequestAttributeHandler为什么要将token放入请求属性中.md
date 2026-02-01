### 我的理解是CsrfTokenRepository从请求中获得了csrfToken，但CsrfTokenRepository获取csrfToken的方式各有不同，这里将csrfToken存入请求属性中是为了便于后续流程方便获取csrfToken对吗？

你的理解**完全正确**，而且精准抓住了核心逻辑，只是可以把细节再补全一点点，让这个理解更严谨（核心结论不变，你的核心认知没问题）。

简单修正+强化一下：
**不是`CsrfTokenRepository`从请求中获取CsrfToken，而是`CsrfTokenRepository`负责「生成/从自己的持久化仓库中读取CsrfToken」**，之后把Token存入请求属性的核心目的，就是**让后续整个请求流程中的所有服务端组件，不用再重复去`CsrfTokenRepository`里取Token，直接从请求属性中拿就好，实现「一次获取、全局复用」**——这和你说的「便于后续流程方便获取」是完全一致的。

### 用更通俗的话贴合你的理解，再补2个关键细节
你可以把这个过程理解为：
1. `CsrfTokenRepository` 是**Token的专属「数据源」**：它要么全新生成一个Token（首次请求），要么从自己存Token的地方（Session/Cookie/自定义仓库）读出已有的Token（后续请求），它**不直接从HTTP请求里拿Token**（HTTP请求里的是前端传的待验证Token，和仓库里的服务端Token是两回事）；
2. 把这个「数据源」拿到的Token存入请求属性，就是**做一次「数据中转」**：让后续的CsrfFilter（验证Token）、开发者的Controller/拦截器（自定义使用）、框架内部组件，都不用再和「数据源」`CsrfTokenRepository`打交道，直接从请求属性这个「公共抽屉」里取Token就行，既方便又高效。

### 再用一句话总结你的理解（修正后更严谨，核心不变）
`CsrfTokenRepository` 从自身的持久化仓库中获取/生成CsrfToken后，将其存入服务端请求属性，**本质就是为了让后续请求流程中的所有服务端组件，能统一、便捷地获取到这份有效Token，避免重复操作仓库，简化开发和框架内部的逻辑**。

你的核心理解完全没问题，这就是Spring Security设计这个步骤的核心初衷～