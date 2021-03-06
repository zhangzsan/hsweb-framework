package org.hswebframework.web.organizational.authorization.simple.handler;

import org.hsweb.ezorm.core.param.Term;
import org.hswebframework.utils.ClassUtils;
import org.hswebframework.web.authorization.Permission;
import org.hswebframework.web.authorization.access.DataAccessConfig;
import org.hswebframework.web.authorization.access.DataAccessHandler;
import org.hswebframework.web.authorization.access.ScopeDataAccessConfig;
import org.hswebframework.web.authorization.annotation.RequiresDataAccess;
import org.hswebframework.web.authorization.define.AuthorizingContext;
import org.hswebframework.web.boost.aop.context.MethodInterceptorHolder;
import org.hswebframework.web.boost.aop.context.MethodInterceptorParamContext;
import org.hswebframework.web.commons.entity.Entity;
import org.hswebframework.web.commons.entity.param.QueryParamEntity;
import org.hswebframework.web.controller.QueryController;
import org.hswebframework.web.entity.organizational.OrganizationalEntity;
import org.hswebframework.web.entity.organizational.authorization.OrgAttachEntity;
import org.hswebframework.web.organizational.authorization.PersonnelAuthorization;
import org.hswebframework.web.organizational.authorization.access.DataAccessType;
import org.hswebframework.web.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * TODO 完成注释
 *
 * @author zhouhao
 */
public abstract class AbstractScopeDataAccessHandler<E> implements DataAccessHandler {
    protected Logger logger = LoggerFactory.getLogger(this.getClass());

    private boolean defaultSuccessOnError = true;

    protected abstract Class<E> getEntityClass();

    protected abstract String getSupportScope();

    protected abstract String getOperationScope(E entity);

    protected abstract void applyScopeProperty(E entity, String value);

    protected abstract Term createQueryTerm(Set<String> scope,AuthorizingContext context);

    protected abstract Set<String> getTryOperationScope(String scopeType, PersonnelAuthorization authorization);

    @Override
    public boolean isSupport(DataAccessConfig access) {
        return access instanceof ScopeDataAccessConfig && access.getType().equals(getSupportScope());
    }

    @Override
    public boolean handle(DataAccessConfig access, AuthorizingContext context) {
        ScopeDataAccessConfig accessConfig = ((ScopeDataAccessConfig) access);
        switch (accessConfig.getAction()) {
            case Permission.ACTION_QUERY:
                return handleQuery(accessConfig, context);
            case Permission.ACTION_GET:
            case Permission.ACTION_DELETE:
            case Permission.ACTION_UPDATE:
                return handleRW(accessConfig, context);
            case Permission.ACTION_ADD:
                return handleAdd(accessConfig, context);
        }
        return false;
    }

    protected PersonnelAuthorization getPersonnelAuthorization() {
        return PersonnelAuthorization.current()
                .orElseThrow(UnsupportedOperationException::new); // TODO: 17-5-23 其他异常?
    }

    protected boolean handleAdd(ScopeDataAccessConfig access, AuthorizingContext context) {
        PersonnelAuthorization authorization = getPersonnelAuthorization();
        Set<String> scopes = authorization.getRootOrgId();
        String scope = null;
        if (scopes.size() == 0) return true;
        else if (scopes.size() == 1) scope = scopes.iterator().next();
        else logger.warn("existing many scope :{} , try use config.", scopes);
        scopes = getTryOperationScope(access).stream().map(String::valueOf).collect(Collectors.toSet());
        if (scope == null && scopes.size() == 1) {
            scope = scopes.iterator().next();
        }
        if (scope != null) {
            String finalScopeId = scope;
            context.getParamContext().getParams().values().stream()
                    .filter(getEntityClass()::isInstance)
                    .map(getEntityClass()::cast)
                    .forEach(entity -> applyScopeProperty(entity, finalScopeId));
        } else {
            logger.warn("scope is null!");
        }
        return defaultSuccessOnError;
    }

    protected boolean handleRW(ScopeDataAccessConfig access, AuthorizingContext context) {
        //获取注解
        Object id = context.getParamContext()
                .<String>getParameter(
                        context.getDefinition()
                                .getDataAccessDefinition()
                                .getIdParameterName())
                .orElse(null);

        Object controller = context.getParamContext().getTarget();
        Set<String> ids = getTryOperationScope(access);
        String errorMsg;
        //通过QueryController获取QueryService
        //然后调用selectByPk 查询旧的数据,进行对比
        if (controller instanceof QueryController) {
            //判断是否满足条件(泛型为 getEntityClass)
            Class entityType = ClassUtils.getGenericType(controller.getClass(), 0);
            if (ClassUtils.instanceOf(entityType, getEntityClass())) {
                @SuppressWarnings("unchecked")
                QueryService<E, Object> queryService = ((QueryController<E, Object, Entity>) controller).getService();
                E oldData = queryService.selectByPk(id);
                return !(oldData != null && !ids.contains(getOperationScope(oldData)));
            } else {
                errorMsg = "GenericType[0] not instance of " + getEntityClass();
            }
        } else {
            errorMsg = "target not instance of QueryController";
        }
        logger.warn("do handle {} fail,because {}", access.getAction(), errorMsg);
        return defaultSuccessOnError;
    }

    protected Set<String> getTryOperationScope(ScopeDataAccessConfig access) {
        if (DataAccessType.SCOPE_TYPE_CUSTOM.equals(access.getScopeType()))
            return access.getScope().stream().map(String::valueOf).collect(Collectors.toSet());
        return getTryOperationScope(access.getScopeType(), getPersonnelAuthorization());
    }

    protected boolean handleQuery(ScopeDataAccessConfig access, AuthorizingContext context) {
        Entity entity = context.getParamContext().getParams()
                .values().stream()
                .filter(Entity.class::isInstance)
                .map(Entity.class::cast)
                .findAny().orElse(null);
        if (entity == null) {
            logger.warn("try validate query access, but query entity is null or not instance of org.hswebframework.web.commons.entity.Entity");
            return defaultSuccessOnError;
        }
        Set<String> scope = getTryOperationScope(access);
        if (scope.isEmpty()) {
            logger.warn("try validate query access,but config is empty!");
            return defaultSuccessOnError;
        }
        if (entity instanceof QueryParamEntity) {
            if (logger.isDebugEnabled())
                logger.debug("try rebuild query param ...");
            QueryParamEntity queryParamEntity = ((QueryParamEntity) entity);
            //重构查询条件
            //如: 旧的条件为 where name =? or name = ?
            //重构后为: where org_id in(?,?) and (name = ? or name = ?)
            List<Term> oldParam = queryParamEntity.getTerms();
            //清空旧的查询条件
            queryParamEntity.setTerms(new ArrayList<>());
            //添加一个查询条件
            queryParamEntity
                    .addTerm(createQueryTerm(scope,context))
                    //客户端提交的参数 作为嵌套参数
                    .nest().setTerms(oldParam);
        } else {
            logger.warn("try validate query access,but entity not support, QueryParamEntity support now!");
        }
        return true;
    }

    protected boolean genericTypeInstanceOf(Class type, AuthorizingContext context) {
        Class entity = ClassUtils.getGenericType(context.getParamContext().getTarget().getClass());
        return null != entity && ClassUtils.instanceOf(entity, type);
    }
}
