package net.cloud.service.impl;

import lombok.extern.slf4j.Slf4j;
import net.cloud.component.ShortLinkComponent;
import net.cloud.config.RabbitMQConfig;
import net.cloud.controller.request.ShortLinkAddRequest;
import net.cloud.controller.request.ShortLinkDelRequest;
import net.cloud.controller.request.ShortLinkPageRequest;
import net.cloud.controller.request.ShortLinkUpdateRequest;
import net.cloud.enums.DomainTypeEnum;
import net.cloud.enums.EventMessageType;
import net.cloud.enums.ShortLinkStateEnum;
import net.cloud.interceptor.LoginInterceptor;
import net.cloud.manager.DomainManager;
import net.cloud.manager.GroupCodeMappingManager;
import net.cloud.manager.LinkGroupManager;
import net.cloud.manager.ShortLinkManager;
import net.cloud.model.*;
import net.cloud.service.ShortLinkService;
import net.cloud.utils.CommonUtil;
import net.cloud.utils.IDUtil;
import net.cloud.utils.JsonData;
import net.cloud.utils.JsonUtil;
import net.cloud.vo.ShortLinkVO;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ShortLinkServiceImpl implements ShortLinkService {

    @Autowired
    private ShortLinkManager shortLinkManager;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitMQConfig rabbitMQConfig;

    @Autowired
    private DomainManager domainManager;

    @Autowired
    private LinkGroupManager linkGroupManager;

    @Autowired
    private ShortLinkComponent shortLinkComponent;

    @Autowired
    private GroupCodeMappingManager groupCodeMappingManager;

    @Autowired
    private RedisTemplate<Object,Object> redisTemplate;

    /**
     * ????????????
     * @param shortLinkCode
     * @return
     */
    @Override
    public ShortLinkVO parseShortLinkCode(String shortLinkCode) {
        ShortLinkDO byShortLinkCode = shortLinkManager.findByShortLinkCode(shortLinkCode);
        if(byShortLinkCode == null){
            return null;
        }
        ShortLinkVO shortLinkVO = new ShortLinkVO();
        BeanUtils.copyProperties(byShortLinkCode,shortLinkVO);
        return shortLinkVO;
    }

    /**
     * ???????????? ?????????????????????
     * @param request
     * @return
     */
    @Override
    public JsonData createShortLink(ShortLinkAddRequest request) {
        Long accountNo = LoginInterceptor.threadLocal.get().getAccountNo();

        String newOriginalUrl = CommonUtil.addUrlPrefix(request.getOriginalUrl());
        request.setOriginalUrl(newOriginalUrl);

        EventMessage eventMessage = EventMessage.builder()
                .accountNo(accountNo)
                .content(JsonUtil.obj2Json(request))
                .messageId(IDUtil.geneSnowFlakeID().toString())
                .eventMessageType(EventMessageType.SHORT_LINK_ADD.name())
                .build();
        rabbitTemplate.convertAndSend(rabbitMQConfig.getShortLinkEventExchange(),rabbitMQConfig.getShortLinkAddRoutingKey(),eventMessage);
        return JsonData.buildSuccess();
    }

    /**
     * ?????????????????? C???
     *
     * 1?????????????????????????????????
     * 2???????????????????????????
     * 3?????????????????????
     * 4??????????????????
     * 5?????????
     * 6??????????????????????????????
     * 7?????????????????????
     * 8??????????????????
     * @param eventMessage
     * @return
     */
    @Override
    public boolean handleAddShortLink(EventMessage eventMessage) {
        Long accountNo = eventMessage.getAccountNo();
        String eventMessageType = eventMessage.getEventMessageType();
        //???????????????????????????????????????????????????????????????????????????
        ShortLinkAddRequest shortLinkAddRequest = JsonUtil.json2Obj(eventMessage.getContent(),ShortLinkAddRequest.class);
        //??????????????????
        DomainDO domainDO = checkDomain(shortLinkAddRequest.getDomainType(),shortLinkAddRequest.getDomainId(),accountNo);
        //?????????????????????
        LinkGroupDO linkGroupDO = checkLinkGroup(shortLinkAddRequest.getGroupId(), accountNo);

        //?????????????????????
        boolean duplicateCodeFlag = false;

        //??????????????????
        String originalUrlDigest = CommonUtil.MD5(shortLinkAddRequest.getOriginalUrl());

        //???????????????
        String shortLinkCode = shortLinkComponent.createShortLinkCode(shortLinkAddRequest.getOriginalUrl());

        //??????
        //key1???????????????ARGV[1]???accountNo,ARGV[2]???????????????
        String script = "if redis.call('EXISTS',KEYS[1])==0 " +
                "then redis.call('set',KEYS[1],ARGV[1]); " +
                "redis.call('expire',KEYS[1],ARGV[2]); " +
                "return 1;" +
                " elseif redis.call('get',KEYS[1]) == ARGV[1] " +
                "then return 2;" +
                " else return 0; " +
                "end;";

        Long result = redisTemplate.execute(new DefaultRedisScript<>(script, Long.class), Arrays.asList(shortLinkCode), accountNo,100);

        //????????????
        if(result>0){
            if(EventMessageType.SHORT_LINK_ADD_LINK.name().equalsIgnoreCase(eventMessageType)){
                //C ?????????

                //???????????????????????????????????????????????????????????????????????????
                ShortLinkDO shortLinkCodeDOInDB = shortLinkManager.findByShortLinkCode(shortLinkCode);
                if(shortLinkCodeDOInDB==null){
                    //???????????????
                    ShortLinkDO shorLinkDo = ShortLinkDO.builder()
                            .accountNo(accountNo)
                            .code(shortLinkCode)
                            .title(shortLinkAddRequest.getTitle())
                            .originalUrl(shortLinkAddRequest.getOriginalUrl())
                            .domain(domainDO.getValue())
                            .groupId(linkGroupDO.getId())
                            .expired(shortLinkAddRequest.getExpired())
                            .sign(originalUrlDigest)
                            .state(ShortLinkStateEnum.ACTIVE.name())
                            .del(0)
                            .build();

                    shortLinkManager.addShortLink(shorLinkDo);
                    return true;
                }else {
                    //?????????????????????
                    log.error("C?????????????????????{}",eventMessage);
                    duplicateCodeFlag = true;
                }


            }else if(EventMessageType.SHORT_LINK_ADD_MAPPING.name().equalsIgnoreCase(eventMessageType)){

                //???????????????????????????
                GroupCodeMappingDO groupCodeMappingDOInDB = groupCodeMappingManager.findByCodeAndGroupId(shortLinkCode,linkGroupDO.getId(),accountNo);

                if(groupCodeMappingDOInDB==null){
                    //B ?????????
                    GroupCodeMappingDO groupCodeMappingDO = GroupCodeMappingDO.builder()
                            .accountNo(accountNo)
                            .code(shortLinkCode)
                            .title(shortLinkAddRequest.getTitle())
                            .originalUrl(shortLinkAddRequest.getOriginalUrl())
                            .domain(domainDO.getValue())
                            .groupId(linkGroupDO.getId())
                            .expired(shortLinkAddRequest.getExpired())
                            .sign(originalUrlDigest)
                            .state(ShortLinkStateEnum.ACTIVE.name())
                            .del(0)
                            .build();
                    groupCodeMappingManager.add(groupCodeMappingDO);
                    return true;
                }else {
                    //?????????????????????
                    log.error("B?????????????????????{}",eventMessage);
                    duplicateCodeFlag = true;
                }
            }
        }else {
            //?????????????????????100ms????????????
            //????????????????????????????????????????????????????????????????????????
            log.error("???????????????{}",eventMessage);
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            duplicateCodeFlag = true;
        }

        if(duplicateCodeFlag){
            //??????????????????????????????????????????
            String newOriginalVersion = CommonUtil.addUrlPrefixVersion(shortLinkAddRequest.getOriginalUrl());
            shortLinkAddRequest.setOriginalUrl(newOriginalVersion);
            eventMessage.setContent(JsonUtil.obj2Json(shortLinkAddRequest));
            log.warn("????????????????????????????????????:{}",eventMessage);
            //???????????????????????????
            handleAddShortLink(eventMessage);
        }

        return false;
    }

    @Override
    public boolean handleUpdateShortLink(EventMessage eventMessage) {
        Long accountNo = eventMessage.getAccountNo();
        String messageType = eventMessage.getEventMessageType();
        ShortLinkUpdateRequest request = JsonUtil.json2Obj(eventMessage.getContent(), ShortLinkUpdateRequest.class);

        //????????????????????????
        DomainDO domainDO = checkDomain(request.getDomainType(), request.getDomainId(), accountNo);

        if(EventMessageType.SHORT_LINK_UPDATE_LINK.name().equalsIgnoreCase(messageType)){
            //C???

            //??????????????????????????????????????????????????????????????????
            //C???????????????????????????????????????????????????
            ShortLinkDO shortLinkDO = ShortLinkDO.builder()
                    .code(request.getCode()).title(request.getTitle())
                    .accountNo(accountNo).domain(domainDO.getValue()).build();

            int rows = shortLinkManager.update(shortLinkDO);
            log.debug("??????C????????????rows={}",rows);
            return true;
        }else if (EventMessageType.SHORT_LINK_UPDATE_MAPPING.name().equalsIgnoreCase(messageType)){
            //B???
            //B?????????account_no???group_id
            GroupCodeMappingDO groupCodeMappingDO = GroupCodeMappingDO.builder().id(request.getMappingId()).groupId(request.getGroupId()).accountNo(accountNo)
                    .title(request.getTitle()).domain(domainDO.getValue()).build();
            int rows = groupCodeMappingManager.update(groupCodeMappingDO);
            log.debug("??????B????????????rows={}",rows);
            return true;
        }

        return false;
    }

    @Override
    public boolean handleDelShortLink(EventMessage eventMessage) {
        Long accountNo = eventMessage.getAccountNo();
        String eventMessageType = eventMessage.getEventMessageType();

        ShortLinkDelRequest request = JsonUtil.json2Obj(eventMessage.getContent(), ShortLinkDelRequest.class);

        //C?????????
        if(EventMessageType.SHORT_LINK_DEL_LINK.name().equalsIgnoreCase(eventMessageType)){
            ShortLinkDO shortLinkDO = ShortLinkDO.builder().code(request.getCode()).accountNo(accountNo).build();
            int rows = shortLinkManager.del(shortLinkDO);
            log.debug("??????C??????rows={}",rows);
            return true;
        }else if(EventMessageType.SHORT_LINK_DEL_MAPPING.name().equalsIgnoreCase(eventMessageType)){
            //B???
            GroupCodeMappingDO groupCodeMappingDO = GroupCodeMappingDO.builder().id(request.getMappingId())
                    .accountNo(accountNo).groupId(request.getGroupId()).build();
            int rows = groupCodeMappingManager.del(groupCodeMappingDO);
            log.debug("??????B??????rows={}",rows);
            return true;
        }

        return false;
    }

    /**
     * ????????????
     * ???B????????????group_code_mapping
     * @param request
     * @return
     */
    @Override
    public Map<String, Object> pageByGroupId(ShortLinkPageRequest request) {
        long accountNo = LoginInterceptor.threadLocal.get().getAccountNo();
        Map<String, Object> result = groupCodeMappingManager.pageShortLinkByGroupId(request.getPage(), request.getSize(), accountNo, request.getGroupId());
        return result;
    }

    @Override
    public JsonData update(ShortLinkUpdateRequest request) {
        Long accountNo = LoginInterceptor.threadLocal.get().getAccountNo();

        EventMessage eventMessage = EventMessage.builder()
                .accountNo(accountNo)
                .content(JsonUtil.obj2Json(request))
                .messageId(IDUtil.geneSnowFlakeID().toString())
                .eventMessageType(EventMessageType.SHORT_LINK_UPDATE.name())
                .build();

        rabbitTemplate.convertAndSend(rabbitMQConfig.getShortLinkEventExchange(),rabbitMQConfig.getShortLinkUpdateRoutingKey(),eventMessage);

        return JsonData.buildSuccess();
    }

    @Override
    public JsonData del(ShortLinkDelRequest request) {
        Long accountNo = LoginInterceptor.threadLocal.get().getAccountNo();

        EventMessage eventMessage = EventMessage.builder()
                .accountNo(accountNo)
                .content(JsonUtil.obj2Json(request))
                .messageId(IDUtil.geneSnowFlakeID().toString())
                .eventMessageType(EventMessageType.SHORT_LINK_DEL.name())
                .build();

        rabbitTemplate.convertAndSend(rabbitMQConfig.getShortLinkEventExchange(),rabbitMQConfig.getShortLinkDelRoutingKey(),eventMessage);

        return JsonData.buildSuccess();
    }

    /**
     * ????????????????????????
     * @param domainType
     * @param domainId
     * @param accountNo
     * @return
     */
    private DomainDO checkDomain(String domainType,Long domainId,Long accountNo){
        DomainDO domainDO;

        //?????????????????????????????????
        if(DomainTypeEnum.CUSTOM.name().equalsIgnoreCase(domainType)){
            //????????????id???????????????????????????
            domainDO = domainManager.findById(domainId, accountNo);
        }else {
            domainDO = domainManager.findByDomainTypeAndId(domainId,DomainTypeEnum.OFFICIAL);
        }

        Assert.notNull(domainDO,"???????????????");
        return domainDO;
    }

    /**
     * ?????????????????????
     * @param groupId
     * @param accountNo
     * @return
     */
    private LinkGroupDO checkLinkGroup(Long groupId,Long accountNo){
        LinkGroupDO linkGroupDO = linkGroupManager.detail(groupId, accountNo);
        Assert.notNull(linkGroupDO,"???????????????");
        return linkGroupDO;
    }
}
