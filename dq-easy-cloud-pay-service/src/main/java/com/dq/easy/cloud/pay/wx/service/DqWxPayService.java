package com.dq.easy.cloud.pay.wx.service;

import com.dq.easy.cloud.model.common.http.constant.DqHttpConstant.MethodType;
import com.dq.easy.cloud.model.common.http.pojo.dto.DqHttpConfigStorageDTO;
import com.dq.easy.cloud.model.common.map.utils.DqMapUtils;
import com.dq.easy.cloud.model.common.qrcode.utils.DqQrCodeUtil;
import com.dq.easy.cloud.model.common.sign.utils.DqSignUtils;
import com.dq.easy.cloud.model.common.string.utils.DqStringUtils;
import com.dq.easy.cloud.model.common.xml.utils.DqXMLUtils;
import com.dq.easy.cloud.model.exception.bo.DqBaseBusinessException;
import com.dq.easy.cloud.pay.model.base.api.DqBasePayService;
import com.dq.easy.cloud.pay.model.base.config.dto.DqPayConfigStorage;
import com.dq.easy.cloud.pay.model.base.constant.DqPayErrorCode;
import com.dq.easy.cloud.pay.model.payment.dto.DqPayMessageDTO;
import com.dq.easy.cloud.pay.model.payment.dto.DqPayOrderDTO;
import com.dq.easy.cloud.pay.model.payment.dto.DqPayOutMessageDTO;
import com.dq.easy.cloud.pay.model.refund.dto.DqRefundOrderDTO;
import com.dq.easy.cloud.pay.model.transaction.dto.DqTransferOrder;
import com.dq.easy.cloud.pay.model.transaction.inf.DqTransactionType;
import com.dq.easy.cloud.pay.wx.pojo.bo.DqWxTransactionType;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 微信支付服务
 */
public class DqWxPayService extends DqBasePayService {
	protected final Log LOG = LogFactory.getLog(this.getClass());

	/**
	 * 微信请求地址
	 */
	public final static String URI = "https://api.mch.weixin.qq.com/";
	/**
	 * 沙箱
	 */
	public final static String SANDBOXNEW = "sandboxnew/";
	public final static String SUCCESS = "SUCCESS";
	public final static String RETURN_CODE = "return_code";
	public final static String RETURN_MSG = "return_msg";
	public final static String RESULT_CODE = "result_code";
	public final static String ERR_CODE = "err_code";
	public final static String RESULT_MSG = "result_msg";
	public final static String SIGN = "sign";
	public final static String SPBILL_CREATE_IP_DEFAULT = "192.168.1.1";
	
	/**
	 * 创建支付服务
	 * 
	 * @param payConfigStorage
	 *            微信对应的支付配置
	 */
	public DqWxPayService(DqPayConfigStorage payConfigStorage) {
		super(payConfigStorage);
	}

	/**
	 * 创建支付服务
	 * 
	 * @param payConfigStorage
	 *            微信对应的支付配置
	 * @param configStorage
	 *            微信对应的网络配置，包含代理配置、ssl证书配置
	 */
	public DqWxPayService(DqPayConfigStorage payConfigStorage, DqHttpConfigStorageDTO configStorage) {
		super(payConfigStorage, configStorage);
	}

	/**
	 * 根据交易类型获取url
	 *
	 * @param transactionType
	 *            交易类型
	 *
	 * @return 请求url
	 */
	private String getUrl(DqTransactionType transactionType) {

		return URI + (payConfigStorage.isTest() ? SANDBOXNEW : "") + transactionType.getMethod();
	}

	/**
	 * 回调校验
	 *
	 * @param params
	 *            回调回来的参数集
	 * @return 签名校验 true通过
	 */
	@Override
	public boolean verify(Map<String, Object> params) {

		if (!SUCCESS.equals(params.get(RETURN_CODE))) {
			LOG.debug(String.format("微信支付异常：return_code=%s,参数集=%s", params.get(RETURN_CODE), params));
			return false;
		}

		if (null == params.get(SIGN)) {
			LOG.debug("微信支付异常：签名为空！out_trade_no=" + params.get("out_trade_no"));
			return false;
		}
		try {
			return signVerify(params, (String) params.get(SIGN)) && verifySource((String) params.get("out_trade_no"));
		} catch (Exception e) {
			LOG.error(e);
		}
		return false;
	}

	/**
	 * 微信是否也需要再次校验来源，进行订单查询
	 *
	 * @param id
	 *            商户单号
	 * @return true通过
	 */
	@Override
	public boolean verifySource(String id) {
		return true;
	}

	/**
	 * 根据反馈回来的信息，生成签名结果
	 *
	 * @param params
	 *            通知返回来的参数数组
	 * @param sign
	 *            比对的签名结果
	 * @return 生成的签名结果
	 */
	@Override
	public boolean signVerify(Map<String, Object> params, String sign) {
		return DqSignUtils.valueOf(payConfigStorage.getSignType()).verify(params, sign,
				"&key=" + payConfigStorage.getKeyPrivate(), payConfigStorage.getInputCharset());
	}

	/**
	 * 获取公共参数
	 *
	 * @return 公共参数
	 */
	private Map<String, Object> getPublicParameters() {

		Map<String, Object> parameters = new TreeMap<String, Object>();
		parameters.put("appid", payConfigStorage.getAppid());
		parameters.put("mch_id", payConfigStorage.getPid());
		parameters.put("nonce_str", DqSignUtils.randomStr());
		return parameters;

	}

	/**
	 * 微信统一下单接口
	 *
	 * @param order
	 *            支付订单集
	 * @return 下单结果
	 */
	public Map<String, Object> unifiedOrder(DqPayOrderDTO order) {

		//// 统一下单
		Map<String, Object> parameters = getPublicParameters();

		parameters.put("body", order.getSubject());// 购买支付信息
		parameters.put("out_trade_no", order.getOutTradeNo());// 订单号
		parameters.put("spbill_create_ip",
				DqStringUtils.isEmpty(order.getSpbillCreateIp()) ? SPBILL_CREATE_IP_DEFAULT : order.getSpbillCreateIp());
		parameters.put("total_fee", conversion(order.getPrice()));// 总金额单位为分

		parameters.put("attach", order.getBody());
		parameters.put("notify_url", payConfigStorage.getNotifyUrl());
		parameters.put("trade_type", order.getTransactionType().getType());
		((DqWxTransactionType) order.getTransactionType()).setAttribute(parameters, order);

		String sign = createSign(DqSignUtils.parameterText(parameters), payConfigStorage.getInputCharset());
		parameters.put(SIGN, sign);

		String requestXML = DqXMLUtils.getXmlStrFromMap(parameters);
		LOG.debug("requestXML：" + requestXML);
		// 调起支付的参数列表
		@SuppressWarnings("unchecked")
		Map<String, Object> result = requestTemplate.postForObject(getUrl(order.getTransactionType()), requestXML,
				HashMap.class);

		if (!SUCCESS.equals(result.get(RETURN_CODE))) {
			throw DqBaseBusinessException.newInstance(DqMapUtils.getString(result, RETURN_CODE),
					DqMapUtils.getString(result, RETURN_MSG));
		}
		return result;
	}

	/**
	 * 返回创建的订单信息
	 *
	 * @param order
	 *            支付订单
	 * @return 订单信息
	 * @see PayOrder 支付订单信息
	 */
	@Override
	public Map<String, Object> orderInfo(DqPayOrderDTO order) {

		//// 统一下单
		Map<String, Object> result = unifiedOrder(order);

		// 如果是扫码支付或者刷卡付无需处理，直接返回
		if (DqWxTransactionType.NATIVE == order.getTransactionType()
				|| DqWxTransactionType.MICROPAY == order.getTransactionType()
				|| DqWxTransactionType.MWEB == order.getTransactionType()) {
			return result;
		}

		SortedMap<String, Object> params = new TreeMap<String, Object>();

		if (DqWxTransactionType.JSAPI == order.getTransactionType()) {
			params.put("signType", payConfigStorage.getSignType());
			params.put("appId", payConfigStorage.getAppid());
			// 此处必须为String类型 否则会提示找不到timeStamp
			params.put("timeStamp", String.valueOf(System.currentTimeMillis() / 1000));
			params.put("nonceStr", result.get("nonce_str"));
			params.put("package", "prepay_id=" + result.get("prepay_id"));
		} else if (DqWxTransactionType.APP == order.getTransactionType()) {
			params.put("partnerid", payConfigStorage.getPid());
			params.put("appid", payConfigStorage.getAppid());
			params.put("prepayid", result.get("prepay_id"));
			params.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
			params.put("noncestr", result.get("nonce_str"));
			params.put("package", "Sign=WXPay");
		}
		String paySign = createSign(DqSignUtils.parameterText(params), payConfigStorage.getInputCharset());
		params.put(SIGN, paySign);
		return params;

	}

	/**
	 * 生成并设置签名
	 *
	 * @param parameters
	 *            请求参数
	 * @return 请求参数
	 */
	private Map<String, Object> setSign(Map<String, Object> parameters) {
		parameters.put("sign_type", payConfigStorage.getSignType());
		String sign = createSign(DqSignUtils.parameterText(parameters, "&", SIGN, "appId"),
				payConfigStorage.getInputCharset());
		parameters.put(SIGN, sign);
		return parameters;
	}

	/**
	 * 签名
	 *
	 * @param content
	 *            需要签名的内容 不包含key
	 * @param characterEncoding
	 *            字符编码
	 * @return 签名结果
	 */
	@Override
	public String createSign(String content, String characterEncoding) {
		return DqSignUtils.valueOf(payConfigStorage.getSignType().toUpperCase())
				.createSign(content, "&key=" + payConfigStorage.getKeyPrivate(), characterEncoding).toUpperCase();
	}

	/**
	 * 将请求参数或者请求流转化为 Map
	 *
	 * @param parameterMap
	 *            请求参数
	 * @param is
	 *            请求流
	 * @return 获得回调的请求参数
	 */
	@Override
	public Map<String, Object> getParameter2Map(Map<String, String[]> parameterMap, InputStream is) {
		TreeMap<String, Object> map = new TreeMap<String, Object>();
		try {
			return DqXMLUtils.getMapFromInputStream(is, map);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 获取输出消息，用户返回给支付端
	 *
	 * @param code
	 *            状态
	 * @param message
	 *            消息
	 * @return 返回输出消息
	 */
	@Override
	public DqPayOutMessageDTO getPayOutMessage(String code, String message) {
		return DqPayOutMessageDTO.XML().code(code.toUpperCase()).content(message).build();
	}

	/**
	 * 获取成功输出消息，用户返回给支付端 主要用于拦截器中返回
	 *
	 * @param payMessage
	 *            支付回调消息
	 * @return 返回输出消息
	 */
	@Override
	public DqPayOutMessageDTO successPayOutMessage(DqPayMessageDTO payMessage) {
		return DqPayOutMessageDTO.XML().code("Success").content("成功").build();
	}

	/**
	 * 获取输出消息，用户返回给支付端, 针对于web端
	 *
	 * @param orderInfo
	 *            发起支付的订单信息
	 * @param method
	 *            请求方式 "post" "get",
	 * @return 获取输出消息，用户返回给支付端, 针对于web端
	 * @see MethodType 请求类型
	 */
	@Override
	public String buildRequest(Map<String, Object> orderInfo, MethodType method) {
		if (!SUCCESS.equals(orderInfo.get(RETURN_CODE))) {
			throw DqBaseBusinessException.newInstance((String) orderInfo.get(RETURN_CODE),
					(String) orderInfo.get(RETURN_MSG));

		}
		if (DqWxTransactionType.MWEB.name().equals(orderInfo.get("trade_type"))) {
			return String.format("<script type=\"text/javascript\">location.href=\"%s%s\"</script>",
					orderInfo.get("mweb_url"), DqStringUtils.isEmpty(payConfigStorage.getReturnUrl()) ? ""
							: "&redirect_url=" + URLEncoder.encode(payConfigStorage.getReturnUrl()));
		}
		throw new UnsupportedOperationException();

	}

	/**
	 * 获取输出二维码，用户返回给支付端,
	 *
	 * @param order
	 *            发起支付的订单信息
	 * @return 返回图片信息，支付时需要的
	 */
	@Override
	public BufferedImage genQrPay(DqPayOrderDTO order) {
		Map<String, Object> orderInfo = orderInfo(order);
		// 获取对应的支付账户操作工具（可根据账户id）
		if (!SUCCESS.equals(orderInfo.get(RESULT_CODE))) {
			throw DqBaseBusinessException.newInstance(DqMapUtils.getString(orderInfo, RESULT_CODE),
					DqMapUtils.getString(orderInfo, ERR_CODE));
		}

		return DqQrCodeUtil.writeInfoToJpgBuff((String) orderInfo.get("code_url"));
	}

	/**
	 * 刷卡付,pos主动扫码付款
	 *
	 * @param order
	 *            发起支付的订单信息
	 * @return 返回支付结果
	 */
	@Override
	public Map<String, Object> microPay(DqPayOrderDTO order) {
		return orderInfo(order);
	}

	/**
	 * 交易查询接口
	 *
	 * @param transactionId
	 *            微信支付平台订单号
	 * @param outTradeNo
	 *            商户单号
	 * @return 返回查询回来的结果集，支付方原值返回
	 */
	@Override
	public Map<String, Object> query(String transactionId, String outTradeNo) {
		return secondaryInterface(transactionId, outTradeNo, DqWxTransactionType.QUERY);
	}

	/**
	 * 交易关闭接口
	 *
	 * @param transactionId
	 *            支付平台订单号
	 * @param outTradeNo
	 *            商户单号
	 * @return 返回支付方交易关闭后的结果
	 */
	@Override
	public Map<String, Object> close(String transactionId, String outTradeNo) {

		return secondaryInterface(transactionId, outTradeNo, DqWxTransactionType.CLOSE);
	}

	/**
	 * 退款
	 *
	 * @param transactionId
	 *            微信订单号
	 * @param outTradeNo
	 *            商户单号
	 * @param refundAmount
	 *            退款金额
	 * @param totalAmount
	 *            总金额
	 * @return 返回支付方申请退款后的结果
	 * @see #refund(RefundOrder, Callback)
	 */
	@Deprecated
	@Override
	public Map<String, Object> refund(String transactionId, String outTradeNo, BigDecimal refundAmount,
			BigDecimal totalAmount) {

		return refund(new DqRefundOrderDTO(transactionId, outTradeNo, refundAmount, totalAmount));
	}

	/**
	 * 申请退款接口
	 *
	 * @param refundOrder
	 *            退款订单信息
	 * @return 返回支付方申请退款后的结果
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Map<String, Object> refund(DqRefundOrderDTO refundOrder) {
		// 获取公共参数
		Map<String, Object> parameters = getPublicParameters();
		if (null != refundOrder.getTradeNo()) {
			parameters.put("transaction_id", refundOrder.getTradeNo());
		} else {
			parameters.put("out_trade_no", refundOrder.getOutTradeNo());
		}
		parameters.put("out_refund_no", refundOrder.getRefundNo());
		parameters.put("total_fee", conversion(refundOrder.getTotalAmount()));
		parameters.put("refund_fee", conversion(refundOrder.getRefundAmount()));
		parameters.put("op_user_id", payConfigStorage.getPid());

		// 设置签名
		setSign(parameters);
		return requestTemplate.postForObject(getUrl(DqWxTransactionType.REFUND),
				DqXMLUtils.getXmlStrFromMap(parameters), HashMap.class);
	}

	/**
	 * 查询退款
	 *
	 * @param transactionId
	 *            支付平台订单号
	 * @param outTradeNo
	 *            商户单号
	 * @return 返回支付方查询退款后的结果
	 */
	@Override
	public Map<String, Object> refundquery(String transactionId, String outTradeNo) {
		return secondaryInterface(transactionId, outTradeNo, DqWxTransactionType.REFUNDQUERY);
	}

	/**
	 * 目前只支持日账单
	 *
	 * @param billDate
	 *            账单类型，商户通过接口或商户经开放平台授权后其所属服务商通过接口可以获取以下账单类型：trade、signcustomer；
	 *            trade指商户基于支付宝交易收单的业务账单；signcustomer是指基于商户支付宝余额收入及支出等资金变动的帐务账单；
	 * @param billType
	 *            账单时间：日账单格式为yyyy-MM-dd，月账单格式为yyyy-MM。
	 * @return 返回支付方下载对账单的结果
	 */
	@Override
	public Map<String, Object> downloadbill(Date billDate, String billType) {

		// 获取公共参数
		Map<String, Object> parameters = getPublicParameters();

		parameters.put("bill_type", billType);
		// 目前只支持日账单
		DateFormat df = new SimpleDateFormat("yyyyMMdd");
		df.setTimeZone(TimeZone.getTimeZone("GMT+8"));
		parameters.put("bill_date", df.format(billDate));

		// 设置签名
		setSign(parameters);
		String respStr = requestTemplate.postForObject(getUrl(DqWxTransactionType.DOWNLOADBILL),
				DqXMLUtils.getXmlStrFromMap(parameters), String.class);
		if (respStr.indexOf("<") == 0) {
			return DqXMLUtils.getMapFromXmlStr(respStr);
		}

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put(RETURN_CODE, SUCCESS);
		ret.put("return_msg", "ok");
		ret.put("data", respStr);
		return ret;
	}

	/**
	 * @param transactionIdOrBillDate
	 *            支付平台订单号或者账单类型， 具体请 类型为{@link String }或者 {@link Date }
	 *            ，类型须强制限制，类型不对应则抛出异常{@link PayErrorException}
	 * @param outTradeNoBillType
	 *            商户单号或者 账单类型
	 * @param transactionType
	 *            交易类型
	 * @return 返回支付方对应接口的结果
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Map<String, Object> secondaryInterface(Object transactionIdOrBillDate, String outTradeNoBillType,
			DqTransactionType transactionType) {

		if (transactionType == DqWxTransactionType.REFUND) {
			throw DqBaseBusinessException.newInstance(DqPayErrorCode.PAY_GENERAL_INTERFACE_NOT_SUPPORT);
		}

		if (transactionType == DqWxTransactionType.DOWNLOADBILL) {
			if (transactionIdOrBillDate instanceof Date) {
				return downloadbill((Date) transactionIdOrBillDate, outTradeNoBillType);
			}
			throw DqBaseBusinessException.newInstance(DqPayErrorCode.ILLICIT_TYPE_EXCEPTION);
		}

		if (!(null == transactionIdOrBillDate || transactionIdOrBillDate instanceof String)) {
			throw DqBaseBusinessException.newInstance(DqPayErrorCode.ILLICIT_TYPE_EXCEPTION);
		}

		// 获取公共参数
		Map<String, Object> parameters = getPublicParameters();
		if (DqStringUtils.isEmpty((String) transactionIdOrBillDate)) {
			parameters.put("out_trade_no", outTradeNoBillType);
		} else {
			parameters.put("transaction_id", transactionIdOrBillDate);
		}
		// 设置签名
		setSign(parameters);
		return requestTemplate.postForObject(getUrl(transactionType), DqXMLUtils.getXmlStrFromMap(parameters),
				HashMap.class);
	}

	/**
	 * 转账
	 *
	 * @param order
	 *            转账订单
	 *
	 * @return 对应的转账结果
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Map<String, Object> transfer(DqTransferOrder order) {
		Map<String, Object> parameters = new TreeMap<String, Object>();
		// 转账到余额
		// parameters.put("mch_appid", payConfigStorage.getAppid());
		parameters.put("mch_id", payConfigStorage.getPid());
		parameters.put("partner_trade_no", order.getOutNo());
		parameters.put("nonce_str", DqSignUtils.randomStr());
		parameters.put("enc_bank_no", keyPublic(order.getPayeeAccount()));
		parameters.put("enc_true_name", keyPublic(order.getPayeeName()));
		parameters.put("bank_code", order.getBank().getCode());
		parameters.put("amount", conversion(order.getAmount()));
		if (!DqStringUtils.isEmpty(order.getRemark())) {
			parameters.put("desc", order.getRemark());
		}
		parameters.put(SIGN, DqSignUtils.valueOf(payConfigStorage.getSignType()).sign(parameters,
				payConfigStorage.getKeyPrivate(), payConfigStorage.getInputCharset()));
		return getHttpRequestTemplate().postForObject(getUrl(DqWxTransactionType.BANK), parameters, HashMap.class);
	}

	/**
	 * 转账
	 *
	 * @param outNo
	 *            商户转账订单号
	 * @param tradeNo
	 *            支付平台转账订单号
	 *
	 * @return 对应的转账订单
	 */
	@SuppressWarnings("unchecked")
	@Override
	public Map<String, Object> transferQuery(String outNo, String tradeNo) {
		Map<String, Object> parameters = new TreeMap<String, Object>();
		parameters.put("mch_id", payConfigStorage.getPid());
		parameters.put("partner_trade_no", DqStringUtils.isEmpty(outNo) ? tradeNo : outNo);
		parameters.put("nonce_str", DqSignUtils.randomStr());
		parameters.put(SIGN, DqSignUtils.valueOf(payConfigStorage.getSignType()).sign(parameters,
				payConfigStorage.getKeyPrivate(), payConfigStorage.getInputCharset()));
		return getHttpRequestTemplate().postForObject(getUrl(DqWxTransactionType.QUERY_BANK), parameters,
				HashMap.class);
	}

	/**
	 * 元转分
	 * 
	 * @param amount
	 *            元的金额
	 * @return 分的金额
	 */
	public int conversion(BigDecimal amount) {
		return amount.multiply(new BigDecimal(100)).setScale(0, BigDecimal.ROUND_HALF_UP).intValue();
	}

	public String keyPublic(String content) {
		return DqSignUtils.RSA.createSign(content, payConfigStorage.getKeyPublic(), payConfigStorage.getInputCharset());
	}

}
