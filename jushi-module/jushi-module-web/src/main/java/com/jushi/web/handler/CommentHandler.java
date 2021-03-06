package com.jushi.web.handler;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.jushi.api.exception.CheckException;
import com.jushi.api.handler.BaseHandler;
import com.jushi.api.pojo.Result;
import com.jushi.api.pojo.po.ArticlePO;
import com.jushi.api.pojo.po.CommentPO;
import com.jushi.api.pojo.po.SysUserPO;
import com.jushi.web.pojo.dto.IssueCommentDTO;
import com.jushi.web.pojo.query.CommonPageQueryByArticle;
import com.jushi.web.repository.ArticleRepository;
import com.jushi.web.repository.CommentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Date;

/**
 * @author 80795
 * @date 2019/7/7 20:50
 */
@Slf4j
@Component
public class CommentHandler extends BaseHandler<CommentRepository, CommentPO> {
    /**
     * mongo模板
     */
    @Autowired
    private ReactiveMongoTemplate reactiveMongoTemplate;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private ArticleRepository articleRepository;


    /**
     * 根据文章查找评论
     *
     * @param request
     * @return
     */
    public Mono<ServerResponse> commonQueryPageByArticle(ServerRequest request) {
        //转换参数
        MultiValueMap<String, String> params = request.queryParams();
        CommonPageQueryByArticle commonPageQueryByArticle = BeanUtil.mapToBean(params.toSingleValueMap(), CommonPageQueryByArticle.class, false);
        return pageQuery(Mono.just(commonPageQueryByArticle), query -> {
            CommonPageQueryByArticle pageQueryByArticle = (CommonPageQueryByArticle) query;
            return getQueryByArticle(pageQueryByArticle);
        }, commentPOFlux -> {
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON_UTF8).body(commentPOFlux, CommentPO.class);
        });
    }

    /**
     * 根据文章查找评论(SSE)
     *
     * @param request
     * @return
     */
    public Mono<ServerResponse> commonQueryPageByArticleSSE(ServerRequest request) {
        //转换参数
        MultiValueMap<String, String> params = request.queryParams();
        CommonPageQueryByArticle commonPageQueryByArticle = BeanUtil.mapToBean(params.toSingleValueMap(), CommonPageQueryByArticle.class, false);
        return pageQuery(Mono.just(commonPageQueryByArticle), query -> {
            CommonPageQueryByArticle pageQueryByArticle = (CommonPageQueryByArticle) query;
            return getQueryByArticle(pageQueryByArticle);
        }, entityFlux -> {
            return sseReturn(entityFlux);
        });
    }


    /**
     * 发表评论
     */
    public Mono<ServerResponse> issueComment(ServerRequest request) {
        Mono<IssueCommentDTO> IssueCommentMono = request.bodyToMono(IssueCommentDTO.class);
        return IssueCommentMono.flatMap(issueComment -> {
            //校验
            CheckException exception = checkIssueArtcle(issueComment);
            if (exception != null) {
                return Mono.error(exception);
            }

            //拷贝属性
            CommentPO commentPO = new CommentPO();
            //拷贝基本属性
            BeanUtils.copyProperties(issueComment, commentPO);
            //文章
            commentPO.setArticle(ArticlePO.builder().id(issueComment.getArticleId()).build());
            //用户
            commentPO.setSysUser(SysUserPO.builder().id(issueComment.getSysUserId()).build());

            //评论时间
            commentPO.setCreateTime(new Date());

            //父级评论
            if (!StrUtil.isBlank(issueComment.getParentId())) {
                commentPO.setParent(CommentPO.builder().id(issueComment.getParentId()).build());
            }

            //祖先评论
            if (!StrUtil.isBlank(issueComment.getAncestorId())) {
                commentPO.setAncestor(CommentPO.builder().id(issueComment.getAncestorId()).build());
            }


            Mono<CommentPO> saveComment = commentRepository.save(commentPO);
            //评论文章
            Mono<ArticlePO> articlePOMono = articleRepository.findById(issueComment.getArticleId());
            return saveComment.flatMap(comment -> {

                return articlePOMono.flatMap(articlePO -> {
                    //评论+1
                    Long commentCount = articlePO.getCommentCount();
                    articlePO.setCommentCount(commentCount == null ? 1 : commentCount + 1);
                    return articleRepository.save(articlePO).flatMap(svArticle -> {
                        return ServerResponse.ok()
                                .body(Mono.just(Result.success("发表成功", comment))
                                        , Result.class);
                    });

                });
            });
        })
                .switchIfEmpty(ServerResponse.ok().body(Mono.just(Result.error(StrUtil.format("{} 发表评论 参数不能为null", IssueCommentDTO.class.getName()))), Result.class));
    }

    /**
     * 评论分页查找条件
     * @param pageQueryByArticle
     * @return
     */
    private Query getQueryByArticle(CommonPageQueryByArticle pageQueryByArticle) {
        Query query = new Query();
        Criteria criteria = new Criteria();
        criteria = criteria.and("article").is(pageQueryByArticle.getArticleId());
        query.addCriteria(criteria);
        return query;
    }

    /**
     * 文章发表校验
     * @param comment
     * @return
     */
    private CheckException checkIssueArtcle(IssueCommentDTO comment) {
        if (StrUtil.isBlank(comment.getSysUserId())) {
            return new CheckException("用户", "请登录");
        }
        if (StrUtil.isBlank(comment.getArticleId())) {
            return new CheckException("文章", "请选择文章");
        }
        if (StrUtil.isBlank(comment.getContent())) {
            return new CheckException("内容", "请输入内容");
        }
        return null;
    }


}
